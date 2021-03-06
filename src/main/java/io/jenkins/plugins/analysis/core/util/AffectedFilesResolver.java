package io.jenkins.plugins.analysis.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.eclipse.collections.impl.factory.Lists;

import edu.hm.hafner.analysis.FilteredLog;
import edu.hm.hafner.analysis.Issue;
import edu.hm.hafner.analysis.Report;
import edu.hm.hafner.util.PathUtil;
import edu.hm.hafner.util.VisibleForTesting;

import hudson.FilePath;
import hudson.model.Run;
import hudson.remoting.VirtualChannel;

/**
 * Copies all affected files that are referenced in at least one of the issues to Jenkins build folder. These files can
 * be inspected in the UI later on.
 *
 * @author Ullrich Hafner
 */
public class AffectedFilesResolver {
    /** Sub folder with the affected files. */
    public static final String AFFECTED_FILES_FOLDER_NAME = "files-with-issues";

    /**
     * Returns whether the affected file in Jenkins' build folder does exist and is readable.
     *
     * @param run
     *         the run referencing the build folder
     * @param issue
     *         the issue in the affected file
     *
     * @return the file
     */
    public static boolean hasAffectedFile(final Run<?, ?> run, final Issue issue) {
        return canAccess(getFile(run, issue.getFileName()));
    }

    private static boolean canAccess(final Path file) {
        return Files.isReadable(file);
    }

    /**
     * Returns the affected file in Jenkins' build folder.
     *
     * @param build
     *         the build
     * @param fileName
     *         the file name of the file to read from the build folder ^
     *
     * @return the file
     * @throws IOException
     *         if the file could not be found
     */
    static InputStream asStream(final Run<?, ?> build, final String fileName) throws IOException {
        return Files.newInputStream(getFile(build, fileName));
    }

    /**
     * Returns the affected file in Jenkins' build folder.
     *
     * @param run
     *         the run referencing the build folder
     * @param fileName
     *         the file name in the folder of affected files
     *
     * @return the file
     */
    public static Path getFile(final Run<?, ?> run, final String fileName) {
        return run.getRootDir().toPath()
                .resolve(AFFECTED_FILES_FOLDER_NAME)
                .resolve(getTempName(fileName));
    }

    /**
     * Returns a file name for a temporary file that will hold the contents of the source.
     *
     * @return the temporary name
     */
    private static String getTempName(final String fileName) {
        return Integer.toHexString(fileName.hashCode()) + ".tmp";
    }

    /**
     * Copies all files with issues from the workspace to the build folder.
     *
     * @param report
     *         the issues
     * @param affectedFilesFolder
     *         directory to store the copied files in
     * @param agentWorkspace
     *         directory of the workspace in the agent, all source files must be part of this directory
     * @param additionalPaths
     *         additional paths that may contain the affected files
     *
     * @throws InterruptedException
     *         if the user cancels the processing
     */
    public void copyAffectedFilesToBuildFolder(final Report report, final FilePath affectedFilesFolder,
            final FilePath agentWorkspace, final FilePath... additionalPaths) throws InterruptedException {
        copyAffectedFilesToBuildFolder(report, new RemoteFacade(affectedFilesFolder, agentWorkspace, additionalPaths));
    }

    /**
     * Copies all files with issues from the workspace to the build folder.
     *
     * @param report
     *         the issues
     * @param affectedFilesFolder
     *         directory to store the copied files in
     * @param agentWorkspace
     *         directory of the workspace in the agent, all source files must be part of this directory
     * @param additionalPaths
     *         additional paths that may contain the affected files
     *
     * @throws InterruptedException
     *         if the user cancels the processing
     */
    public void copyAffectedFilesToBuildFolder(final Report report, final FilePath affectedFilesFolder,
            final FilePath agentWorkspace, final Collection<String> additionalPaths) throws InterruptedException {
        copyAffectedFilesToBuildFolder(report, new RemoteFacade(affectedFilesFolder, agentWorkspace,
                additionalPaths.stream().map(agentWorkspace::child).toArray(FilePath[]::new)));
    }

    @VisibleForTesting
    void copyAffectedFilesToBuildFolder(final Report report, final RemoteFacade remoteFacade)
            throws InterruptedException {
        int copied = 0;
        int notFound = 0;
        int notInWorkspace = 0;

        FilteredLog log = new FilteredLog(report,
                "Can't copy some affected workspace files to Jenkins build folder:");
        Set<String> files = report.getFiles();
        files.remove("-");
        for (String file : files) {
            if (remoteFacade.exists(file)) {
                if (remoteFacade.isInWorkspace(file)) {
                    try {
                        remoteFacade.copy(file);
                        copied++;
                    }
                    catch (IOException exception) {
                        log.logError("- '%s', IO exception has been thrown: %s", file, exception);
                    }
                }
                else {
                    notInWorkspace++;
                }
            }
            else {
                notFound++;
            }
        }

        report.logInfo("-> %d copied, %d not in workspace, %d not-found, %d with I/O error",
                copied, notInWorkspace, notFound, log.size());
        log.logSummary();
    }

    static class RemoteFacade {
        private final VirtualChannel channel;
        private final FilePath affectedFilesFolder;
        private final List<FilePath> sourceDirectories;

        RemoteFacade(final FilePath affectedFilesFolder, final FilePath workspace, final FilePath... sourceFolders) {
            channel = workspace.getChannel();
            this.affectedFilesFolder = affectedFilesFolder;
            sourceDirectories = Lists.mutable.with(sourceFolders).with(workspace).toList();
        }

        boolean exists(final String fileName) {
            try {
                return createFile(fileName).exists();
            }
            catch (IOException | InterruptedException exception) {
                return false;
            }
        }

        private FilePath createFile(final String fileName) {
            return new FilePath(channel, fileName);
        }

        /**
         * Checks whether the source file is in the workspace. Due to security reasons copying of files outside of the
         * workspace is prohibited.
         *
         * @param fileName
         *         the file name of the source
         *
         * @return {@code true} if the file is in the workspace, {@code false} otherwise
         */
        boolean isInWorkspace(final String fileName) {
            PathUtil pathUtil = new PathUtil();
            String sourceFile = pathUtil.getAbsolutePath(createFile(fileName).getRemote());

            return sourceDirectories
                    .stream()
                    .map(sourceFolder -> pathUtil.getAbsolutePath(sourceFolder.getRemote()))
                    .anyMatch(sourceFile::startsWith);
        }

        public void copy(final String fileName) throws IOException, InterruptedException {
            FilePath remoteFile = createFile(fileName);
            FilePath buildFolderCopy = affectedFilesFolder.child(getTempName(remoteFile.getRemote()));
            remoteFile.copyTo(buildFolderCopy);
        }
    }
}
