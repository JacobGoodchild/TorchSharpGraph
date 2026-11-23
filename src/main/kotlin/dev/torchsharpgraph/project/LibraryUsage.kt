package dev.torchsharpgraph.project

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Decides whether a folder holds a .NET project that uses the TorchSharpGraph library, by reading project files
 * (.csproj, .fsproj, Directory.Packages.props, ...). This works the same in Rider and in other IDEs.
 */
object LibraryUsage {
    private val skipFolders = setOf(".git", ".idea", ".vs", "bin", "obj", "node_modules", "packages", ".gradle", "build", "out", ".intellijPlatform")
    private val projectFile = Regex(".*\\.(csproj|fsproj|vbproj)$|^Directory\\.(Packages|Build)\\.props$|^packages\\.config$", RegexOption.IGNORE_CASE)
    private val reference = Regex("""(PackageReference|PackageVersion|ProjectReference|package)\s[^>]*TorchSharpGraph""", RegexOption.IGNORE_CASE)

    const val MAX_DEPTH = 6
    const val MAX_FILES = 3000

    fun usesLibrary(root: Path): Boolean {
        if (!Files.isDirectory(root)) return false
        var found = false
        var seen = 0
        try {
            Files.walkFileTree(root, emptySet(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root && dir.fileName.toString() in skipFolders) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++seen > MAX_FILES) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile && attrs.size() < 2_000_000 && projectFile.matches(file.fileName.toString()) && mentionsLibrary(file)) {
                        found = true
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
        }
        return found
    }

    fun mentionsLibrary(file: Path): Boolean = try {
        reference.containsMatchIn(Files.readString(file))
    } catch (_: IOException) {
        false
    } catch (_: java.nio.charset.MalformedInputException) {
        false
    }
}
