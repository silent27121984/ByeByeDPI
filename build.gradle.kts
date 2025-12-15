// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
}

// Автоматическая инициализация и обновление submodules перед сборкой
tasks.register<Exec>("initSubmodules") {
    group = "build setup"
    description = "Initialize and update git submodules"
    
    // Используем просто "git", так как он доступен через PATH
    // На Windows Git for Windows добавляет git в PATH автоматически
    commandLine("git", "submodule", "update", "--init", "--recursive")
    
    // Не игнорируем ошибки, чтобы видеть реальные проблемы
    isIgnoreExitValue = false
    
    doFirst {
        println("Initializing and updating git submodules...")
    }
    
    finalizedBy("fixSymlinks")
}

// Задача для исправления символьных ссылок на Windows
tasks.register("fixSymlinks") {
    group = "build setup"
    description = "Fix symlinks in submodules for Windows compatibility"
    
    doLast {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        if (!isWindows) {
            println("Skipping symlink fix (not Windows)")
            return@doLast
        }
        
        println("Fixing symlinks for Windows compatibility...")
        
        // Функция для исправления символьных ссылок в директории
        fun fixSymlinksInDir(includeDir: File) {
            if (!includeDir.exists() || !includeDir.isDirectory) {
                return
            }
            
            includeDir.listFiles()?.forEach { symlinkFile ->
                if (symlinkFile.isFile && symlinkFile.name.endsWith(".h")) {
                    try {
                        val content = symlinkFile.readText().trim()
                        // Если файл содержит только путь (символьная ссылка)
                        if (content.startsWith("../") && content.lines().size == 1) {
                            // Пытаемся найти целевой файл относительно includeDir
                            val targetPath = File(includeDir, content).canonicalFile
                            if (targetPath.exists() && targetPath.isFile) {
                                symlinkFile.writeText(targetPath.readText())
                                println("Fixed symlink: ${symlinkFile.relativeTo(file("app"))} -> ${content}")
                            } else {
                                println("Warning: Could not find target for symlink: ${symlinkFile.relativeTo(file("app"))} (${content})")
                            }
                        }
                    } catch (e: Exception) {
                        // Игнорируем ошибки чтения (возможно, это не символьная ссылка)
                    }
                }
            }
        }
        
        // Исправляем символьные ссылки во всех submodules
        val submodulesBase = file("app/src/main/jni/hev-socks5-tunnel")
        
        // Список всех include директорий для обработки
        val includeDirs = listOf(
            "third-part/hev-task-system/include",
            "third-part/yaml/include",
            "src/core/include"
        )
        
        includeDirs.forEach { dirPath ->
            fixSymlinksInDir(File(submodulesBase, dirPath))
        }
        
        println("Symlink fix completed")
    }
}

// Задача для обновления submodules
tasks.register<Exec>("updateSubmodules") {
    group = "build setup"
    description = "Update git submodules to latest commits"
    
    // Используем просто "git", так как он доступен через PATH
    commandLine("git", "submodule", "update", "--remote", "--recursive")
    
    doFirst {
        println("Updating git submodules to latest commits...")
    }
    
    doLast {
        println("Submodules updated successfully")
    }
}

