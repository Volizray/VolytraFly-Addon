package com.volytrafly.autonav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Plans Autonav detours. Pure logic with no Minecraft types, so it can be tested on its own:
 * everything it needs to know about the world comes through {@link Grid}.
 *
 * <p>The idea is the one Autonav is built around: when the straight line to the destination is
 * blocked, search through cells the player can actually fly through (known air only, never an
 * unloaded chunk) and finish at whichever "frontier" cell gives the quickest way on towards the
 * destination. A frontier cell is a flyable cell that touches an unloaded chunk, so the route to it
 * is air all the way, and from there the world keeps loading and the plan is simply redone. Since
 * every route is an unbroken run of flyable cells that ends somewhere with more world beyond it,
 * the player is never sent into a pocket that was already known to be sealed.
 */
public final class AutonavPlanner {
    private AutonavPlanner() {}

    /** The world, as the planner sees it. Cells are whole blocks: (x, y, z) is the block position. */
    public interface Grid {
        /** Whether the chunk at these chunk coordinates is not loaded. */
        boolean isChunkUnloaded(int chunkX, int chunkZ);

        /**
         * Whether the player fits in this cell. Must be false in an unloaded chunk. A clearance of 0
         * is the normal fit; 1 asks for extra room around the cell too (used after getting stuck).
         */
        boolean isPassable(int x, int y, int z, int clearance);

        /** Whether the player can fly the straight line between two cell centres. */
        boolean lineClear(int x0, int y0, int z0, int x1, int y1, int z1);

        /**
         * What it costs to be in this cell when the player is already stuck in something and has to
         * get out of it. 0 when the player fits and keeps clear of the fluids it avoids, so the cell is
         * somewhere to escape to. Otherwise the price of passing through it: more for a cell inside
         * blocks than for one that is merely in or near a fluid. Negative when the cell can't be
         * entered at all (an unloaded chunk).
         */
        int escapeCost(int x, int y, int z);

        /** Lowest and highest cell Y the player may be routed through (inclusive). */
        int minY();
        int maxY();
    }

    public enum Status {
        /** The route ends within arrival radius of the destination. */
        REACHED_DESTINATION,
        /** The route ends on a frontier cell: air all the way, with unloaded world beyond it. */
        REACHED_FRONTIER,
        /** The search ran out of budget; the route heads for the most promising cell it had. */
        BEST_EFFORT,
        /** Every cell reachable from the start was searched and none touches unloaded world or the destination. */
        SEALED,
        /**
         * The player is in something (blocks, or a fluid it avoids) that the normal search can't lead out
         * of. The route is the cheapest way through it to open space, and ends there.
         */
        ESCAPING
    }

    public static final class Result {
        public final Status status;
        /** Cells from the start to the end of the route, start included. Empty when SEALED. */
        public final List<int[]> cells;
        /** How many cells the search expanded. */
        public final int expanded;

        Result(Status status, List<int[]> cells, int expanded) {
            this.status = status;
            this.cells = cells;
            this.expanded = expanded;
        }
    }

    // Weighted A*: a heuristic weight above 1 makes the search lean hard towards the destination,
    // so it settles on the frontier nearest the destination instead of mapping out every air cell
    private static final double HEURISTIC_WEIGHT = 2.0;
    // A pull back towards the height the flight is held at, so routes don't wander up and down and
    // the search spreads sideways along an obstacle before it climbs the whole face of it
    private static final double LEVEL_BIAS = 0.05;

    private static final class Node {
        final int x, y, z;
        double g, f, h;
        Node parent;
        boolean closed;
        boolean blocked;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private record Entry(Node node, double f) {}

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    private static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

    /** True when the cell touches an unloaded chunk, i.e. there is world beyond it we know nothing about yet. */
    public static boolean isFrontier(Grid grid, int x, int z) {
        return grid.isChunkUnloaded((x + 1) >> 4, z >> 4)
            || grid.isChunkUnloaded((x - 1) >> 4, z >> 4)
            || grid.isChunkUnloaded(x >> 4, (z + 1) >> 4)
            || grid.isChunkUnloaded(x >> 4, (z - 1) >> 4);
    }

    /**
     * Finds a route from start towards (destX, destZ).
     *
     * @param levelY        the height the flight is being held at (route cells stray from it only when they have to)
     * @param arrivalRadius horizontal distance from the destination that counts as having got there
     * @param clearance     passed on to {@link Grid#isPassable}
     * @param maxNodes      how many cells the search may expand before settling for its best candidate
     * @param band          how far above or below levelY the search may stray (Integer.MAX_VALUE for no limit)
     */
    public static Result plan(Grid grid, int[] start, double destX, double destZ, double arrivalRadius,
                              int levelY, int clearance, int maxNodes, int band) {
        HashMap<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>(
            Comparator.comparingDouble((Entry e) -> e.f).thenComparingDouble(e -> e.node.h));

        Node startNode = new Node(start[0], start[1], start[2]);
        startNode.h = horizontalDistance(startNode, destX, destZ);
        startNode.f = HEURISTIC_WEIGHT * startNode.h;
        nodes.put(key(start[0], start[1], start[2]), startNode);
        open.add(new Entry(startNode, startNode.f));

        int expanded = 0;
        while (!open.isEmpty()) {
            Entry entry = open.poll();
            Node cur = entry.node;
            if (cur.closed || entry.f != cur.f) continue; // stale queue entry
            cur.closed = true;

            if (cur.h <= arrivalRadius) {
                return new Result(Status.REACHED_DESTINATION, path(cur), expanded);
            }
            // The start itself doesn't count as somewhere to head for, only cells actually reached
            if (cur.parent != null && isFrontier(grid, cur.x, cur.z)) {
                return new Result(Status.REACHED_FRONTIER, path(cur), expanded);
            }

            if (++expanded > maxNodes) {
                return new Result(Status.BEST_EFFORT, path(cur), expanded);
            }

            for (int[] d : DIRECTIONS) {
                int nx = cur.x + d[0], ny = cur.y + d[1], nz = cur.z + d[2];
                if (ny < grid.minY() || ny > grid.maxY()) continue;
                if (band != Integer.MAX_VALUE && Math.abs(ny - levelY) > band && Math.abs(ny - start[1]) > band) continue;

                long k = key(nx, ny, nz);
                Node next = nodes.get(k);
                if (next == null) {
                    next = new Node(nx, ny, nz);
                    next.blocked = grid.isChunkUnloaded(nx >> 4, nz >> 4) || !grid.isPassable(nx, ny, nz, clearance);
                    next.h = horizontalDistance(next, destX, destZ);
                    nodes.put(k, next);
                }
                if (next.blocked || next.closed) continue;

                double g = cur.g + 1 + LEVEL_BIAS * Math.abs(ny - levelY);
                if (next.parent == null || g < next.g) {
                    next.parent = cur;
                    next.g = g;
                    next.f = g + HEURISTIC_WEIGHT * next.h;
                    open.add(new Entry(next, next.f));
                }
            }
        }

        return new Result(Status.SEALED, Collections.emptyList(), expanded);
    }

    /**
     * Finds the cheapest way out of whatever the player is stuck in. Unlike {@link #plan} this may pass
     * through cells that aren't passable (at the price {@link Grid#escapeCost} gives them), since from
     * inside blocks or a lake every neighbouring cell is one that plan() refuses. It ends at the first
     * cell with room to spare: free itself, and with at least two free neighbours, so that a normal
     * search can carry on from there. SEALED when nothing like that is within radius.
     *
     * @param radius   how many cells from the start (in each direction) the search may go
     * @param maxNodes how many cells the search may expand
     */
    public static Result escape(Grid grid, int[] start, int radius, int maxNodes) {
        HashMap<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble((Entry e) -> e.f));

        // Node.h holds the cell's escape cost in here
        Node startNode = new Node(start[0], start[1], start[2]);
        nodes.put(key(start[0], start[1], start[2]), startNode);
        open.add(new Entry(startNode, 0));

        int expanded = 0;
        while (!open.isEmpty()) {
            Entry entry = open.poll();
            Node cur = entry.node;
            if (cur.closed || entry.f != cur.f) continue;
            cur.closed = true;

            if (cur.parent != null && cur.h == 0 && freeNeighbours(grid, cur) >= 2) {
                return new Result(Status.ESCAPING, path(cur), expanded);
            }
            if (++expanded > maxNodes) break;

            for (int[] d : DIRECTIONS) {
                int nx = cur.x + d[0], ny = cur.y + d[1], nz = cur.z + d[2];
                if (ny < grid.minY() || ny > grid.maxY()) continue;
                if (Math.abs(nx - start[0]) > radius || Math.abs(ny - start[1]) > radius || Math.abs(nz - start[2]) > radius) continue;

                long k = key(nx, ny, nz);
                Node next = nodes.get(k);
                if (next == null) {
                    next = new Node(nx, ny, nz);
                    int cost = grid.escapeCost(nx, ny, nz);
                    next.blocked = cost < 0;
                    next.h = Math.max(cost, 0);
                    nodes.put(k, next);
                }
                if (next.blocked || next.closed) continue;

                double g = cur.g + 1 + next.h;
                if (next.parent == null || g < next.g) {
                    next.parent = cur;
                    next.g = g;
                    next.f = g;
                    open.add(new Entry(next, g));
                }
            }
        }

        return new Result(Status.SEALED, Collections.emptyList(), expanded);
    }

    private static int freeNeighbours(Grid grid, Node n) {
        int free = 0;
        for (int[] d : DIRECTIONS) {
            if (grid.escapeCost(n.x + d[0], n.y + d[1], n.z + d[2]) == 0) free++;
        }
        return free;
    }

    /** How far from the flight height the first, cheaper pass of {@link #planTwoPass} may stray. */
    public static final int NEAR_BAND = 12;

    /**
     * Searches near the flight height first (cheap, and enough for most obstacles), and only if that
     * finds neither the destination nor a frontier, searches the whole height of the world with
     * what is left of the budget.
     */
    public static Result planTwoPass(Grid grid, int[] start, double destX, double destZ, double arrivalRadius,
                                     int levelY, int clearance, int maxNodes) {
        int firstBudget = Math.max(1, maxNodes * 2 / 3);
        Result near = plan(grid, start, destX, destZ, arrivalRadius, levelY, clearance, firstBudget, NEAR_BAND);
        if (near.status == Status.REACHED_DESTINATION || near.status == Status.REACHED_FRONTIER) return near;

        Result full = plan(grid, start, destX, destZ, arrivalRadius, levelY, clearance, Math.max(1, maxNodes - near.expanded), Integer.MAX_VALUE);
        if (full.status == Status.SEALED && near.status == Status.BEST_EFFORT) return near;
        return full;
    }

    private static double horizontalDistance(Node n, double destX, double destZ) {
        return Math.hypot(n.x + 0.5 - destX, n.z + 0.5 - destZ);
    }

    private static List<int[]> path(Node end) {
        ArrayList<int[]> cells = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) cells.add(new int[]{n.x, n.y, n.z});
        Collections.reverse(cells);
        return cells;
    }

    /**
     * Cuts the corners off a route made of single-block steps: from each cell, jumps to the furthest
     * of the next few cells that can be flown to in a straight line.
     */
    public static List<int[]> smooth(Grid grid, List<int[]> cells, int lookahead) {
        if (cells.size() <= 2) return cells;

        ArrayList<int[]> out = new ArrayList<>();
        int i = 0;
        out.add(cells.getFirst());
        while (i < cells.size() - 1) {
            int j = Math.min(cells.size() - 1, i + lookahead);
            while (j > i + 1) {
                int[] a = cells.get(i), b = cells.get(j);
                if (grid.lineClear(a[0], a[1], a[2], b[0], b[1], b[2])) break;
                j--;
            }
            out.add(cells.get(j));
            i = j;
        }
        return out;
    }
}
