plugins {
    id("maven-publish")
    id("net.fabricmc.fabric-loom") version "1.17.21" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.17.21" apply false

    id("com.replaymod.preprocess") version "c5abb4fb12"
}

preprocess {
    strictExtraMappings.set(false)

    val mc12111 = createNode("1.21.11", 1_21_11, "mojang")
    val mc260100 = createNode("26.1", 260100, "mojang")
    val mc260200 = createNode("26.2", 260200, "mojang")
    val mc260300 = createNode("26.3", 260300, "mojang")

    // Source of truth is 26.3; chain points toward main
    mc12111.link(mc260100, file("versions/mapping-1.21.11-26.1.txt"))
    mc260100.link(mc260200, null)
    mc260200.link(mc260300, null)

    for (node in getNodes()) {
        findProject(node.project)
            ?.ext
            ?.set("mcVersion", node.mcVersion)
    }
}
