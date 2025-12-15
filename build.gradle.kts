// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
}

// Автоматическая инициализация и обновление submodules перед сборкой
tasks.register<Exec>("initSubmodules") {
    group = "build setup"
    description = "Initialize and update git submodules"
    
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val gitCmd = if (isWindows) "git.cmd" else "git"
    
    commandLine(gitCmd, "submodule", "update", "--init", "--recursive")
    
    doFirst {
        println("Initializing and updating git submodules...")
    }
    
    doLast {
        println("Submodules initialized and updated successfully")
    }
}

// Задача для обновления submodules
tasks.register<Exec>("updateSubmodules") {
    group = "build setup"
    description = "Update git submodules to latest commits"
    
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val gitCmd = if (isWindows) "git.cmd" else "git"
    
    commandLine(gitCmd, "submodule", "update", "--remote", "--recursive")
    
    doFirst {
        println("Updating git submodules to latest commits...")
    }
    
    doLast {
        println("Submodules updated successfully")
    }
}

