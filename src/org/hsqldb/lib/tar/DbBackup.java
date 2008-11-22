package org.hsqldb.lib.tar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

/**
 * Works with tar archives containing HSQLDB database instance backups.
 * Viz, creating, examining, or extracting these archives.
 * <P/>
 * This class provides OO Tar backup-creation control.
 * The extraction and listing features are implemented only in static fashion
 * in the Main method, which provides a consistend interfact for all three
 * features from the command-line.
 * <P/>
 * For tar creation, the default behavior is to fail if the target archive
 * exists, and to abort if any database change is detected.
 * Use the JavaBean setters to changes this behavior.
 *
 * @see #setOverWrite
 * @see #setAbortUponModify
 * @author Blaine Simpson
 */
public class DbBackup {

    final static public String JARHOUSE = "hbackup.jar";
    final static public String SYNTAX_MSG = "SYNTAX:\n    java -cp path/to/"
        + JARHOUSE + "    (to display this message)\nOR\n" + "    java -cp "
        + JARHOUSE + " --save [--overwrite] tar/path.tar db/base/path\nOR\n"
        + "    java -cp path/to/" + JARHOUSE
        + " --list tar/path.tar [regex1...]\nOR\n" + "    java -cp path/to/"
        + JARHOUSE
        + " --extract [--overwrite] file/path.tar[.gz] db/dir [regex1...]\n"
        + "    (extracts entry files to the specified db/dir).\n"
        + "N.b. the db/base/path includes file base name, like in URLs, "
        + "whereas db/dir is a proper 'directory'.";

    /**
     * Command line invocation to create, examine, or extract HSQLDB database
     * backup tar archives.
     * <P>
     * This class stores tar entries as relative files without specifying
     * parent directories, in what is commonly referred to as <I>tar bomb</I>
     * format.
     * The set of files is small, with known extensions, and the potential
     * inconvenience of messing up the user's current directory is more than
     * compensated by making it easier for the user to restore to a new
     * database URL location at a peer level to the original.
     * <P/>
     * Automatically calculates buffer sizes based on the largest component
     * file (for "save" mode) or tar file size (for other modes).
     * <P/>
     * Run<CODE><PRE>
     *     java -cp path/to/hbackup.jar
     * </PRE></CODE> for syntax help.
     */
    static public void main(String[] sa)
    throws IOException, TarMalformatException {

        try {
            if (sa.length < 1) {
                System.out.println(SYNTAX_MSG);
                System.exit(0);
            }

            if (sa[0].equals("--save")) {
                boolean overWrite = sa.length > 1
                                    && sa[1].equals("--overwrite");

                if (sa.length != (overWrite ? 4
                                            : 3)) {
                    throw new IllegalArgumentException();
                }

                DbBackup backup = new DbBackup(new File(sa[sa.length - 2]),
                                               sa[sa.length - 1]);

                backup.setOverWrite(overWrite);
                backup.write();
            } else if (sa[0].equals("--list")) {
                if (sa.length < 2) {
                    throw new IllegalArgumentException();
                }

                String[] patternStrings = null;

                if (sa.length > 2) {
                    patternStrings = new String[sa.length - 2];

                    for (int i = 2; i < sa.length; i++) {
                        patternStrings[i - 2] = sa[i];
                    }
                }

                new TarReader(new File(sa[1]), TarReader
                    .LIST_MODE, patternStrings, new Integer(DbBackup
                        .generateBufferBlockValue(new File(sa[1]))), null)
                            .read();
            } else if (sa[0].equals("--extract")) {
                boolean overWrite = sa.length > 1
                                    && sa[1].equals("--overwrite");
                int firstPatInd = overWrite ? 4
                                            : 3;

                if (sa.length < firstPatInd) {
                    throw new IllegalArgumentException();
                }

                String[] patternStrings = null;

                if (sa.length > firstPatInd) {
                    patternStrings = new String[sa.length - firstPatInd];

                    for (int i = firstPatInd; i < sa.length; i++) {
                        patternStrings[i - firstPatInd] = sa[i];
                    }
                }

                File tarFile       = new File(sa[overWrite ? 2
                                                           : 1]);
                int  tarReaderMode = overWrite ? TarReader.OVERWRITE_MODE
                                               : TarReader.EXTRACT_MODE;

                new TarReader(
                    tarFile, tarReaderMode, patternStrings,
                    new Integer(DbBackup.generateBufferBlockValue(tarFile)),
                    new File(sa[firstPatInd - 1])).read();
            } else {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException iae) {
            System.err.println("Syntax error.  Run the following for help:");
            System.err.println("    java -cp path/to/" + JARHOUSE);
            System.exit(2);
        }
    }

    /**
     * Instantiate a DbBackup instance for creating a Database Instance backup.
     *
     * Much validation is deferred until the write() method, to prevent
     * problems with files changing between the constructor and the write call.
     */
    public DbBackup(File archiveFile, String dbPath) throws IOException {

        this.archiveFile = archiveFile;

        File dbPathFile = new File(dbPath);

        dbDir        = dbPathFile.getAbsoluteFile().getParentFile();
        instanceName = dbPathFile.getName();
    }

    protected File    dbDir;
    protected File    archiveFile;
    protected String  instanceName;
    protected boolean overWrite       = false;    // Defaults no NO OVERWRITE
    protected boolean abortUponModify = true;     // Defaults to ABORT-UPON-MODIFY

    /**
     * Defaults to false.
     *
     * If false, then attempts to write a tar file that already exist will
     * abort.
     */
    public void setOverWrite(boolean overWrite) {
        this.overWrite = overWrite;
    }

    /**
     * Defaults to true.
     *
     * If true, then the write() method will validate that the database is
     * closed, and it will verify that no DB file changes between when we
     * start writing the tar, and when we finish.
     */
    public void setAbortUponModify(boolean abortUponModify) {
        this.abortUponModify = abortUponModify;
    }

    public boolean getOverWrite() {
        return overWrite;
    }

    public boolean getAbortUponModify() {
        return abortUponModify;
    }

    /**
     * This method always backs up the .properties and .script files.
     * It will back up all of .backup, .data, and .log which exist.
     *
     * If abortUponModify is set, no tar file will be created, and this
     * method will throw.
     *
     * @throws IOException for any of many possible I/O problems
     * @throws IllegalStateException only if abortUponModify is set, and
     *                               database is open or is modified.
     */
    public void write() throws IOException, TarMalformatException {

        File   propertiesFile = new File(dbDir, instanceName + ".properties");
        File   scriptFile     = new File(dbDir, instanceName + ".script");
        File[] componentFiles = new File[] {
            propertiesFile, scriptFile,
            new File(dbDir, instanceName + ".backup"),
            new File(dbDir, instanceName + ".data"),
            new File(dbDir, instanceName + ".log")
        };
        boolean[] existList = new boolean[componentFiles.length];
        long      startTime = new java.util.Date().getTime();

        for (int i = 0; i < existList.length; i++) {
            existList[i] = componentFiles[i].exists();

            if (i < 2 && !existList[i]) {

                // First 2 files are REQUIRED
                throw new FileNotFoundException(
                    "Required file missing: "
                    + componentFiles[i].getAbsolutePath());
            }
        }

        if (abortUponModify) {
            Properties p = new Properties();

            p.load(new FileInputStream(propertiesFile));

            String modifiedString = p.getProperty("modified");

            if (modifiedString != null
                    && (modifiedString.equalsIgnoreCase("yes")
                        || modifiedString.equalsIgnoreCase("true"))) {
                throw new IllegalStateException("'modified' DB property is '"
                                                + modifiedString + "'");
            }
        }

        TarGenerator generator = new TarGenerator(archiveFile, overWrite,
            new Integer(DbBackup.generateBufferBlockValue(componentFiles)));

        for (int i = 0; i < componentFiles.length; i++) {
            if (!componentFiles[i].exists()) {
                continue;

                // We've already verified that required files exist, therefore
                // there is no error condition here.
            }

            generator.queueEntry(componentFiles[i].getName(),
                                 componentFiles[i]);
        }

        generator.write();

        if (abortUponModify) {
            try {
                for (int i = 0; i < componentFiles.length; i++) {
                    if (componentFiles[i].exists()) {
                        if (!existList[i]) {
                            throw new IllegalStateException(
                                "'" + componentFiles[i].getAbsolutePath()
                                + "' disappeared after backup started");
                        }

                        if (componentFiles[i].lastModified() > startTime) {
                            throw new IllegalStateException(
                                "'" + componentFiles[i].getAbsolutePath()
                                + "' changed after backup started");
                        }
                    } else if (existList[i]) {
                        throw new IllegalStateException(
                            "'" + componentFiles[i].getAbsolutePath()
                            + "' appeared after backup started");
                    }
                }
            } catch (IllegalStateException ise) {
                if (!archiveFile.delete()) {
                    System.err.println(
                        "Failed to remove possibly corrupt archive: "
                        + archiveFile.getAbsolutePath());

                    // Be-it-known.  This method can write to stderr if
                    // abortUponModify is true.
                }

                throw ise;
            }
        }
    }

    /**
     * Return a 512-block buffer size suggestion, based on the size of what
     * needs to be read or written, and default and typical JVM constraints.
     * <P/>
     * <B>Algorithm details:</B>
     * <P/>
     * Minimum system I want support is a J2SE system with 256M physical
     * RAM.  This sytem can hold a 61 MB byte array (real 1024^2 M).
     * (61MB with Java 1.6, 62MB with Java 1.4).
     * This decreases to just 60 MB with (pre-production, non-optimized)
     * HSQLDB v. 1.9 on Java 1.6.
     * Allow the user 40 MB of for data (this only corresponds to a much
     * smaller quantity of real data due to the huge overhead of Java and
     * database structures).
     * This allows 20 MB for us to use.  User can easily use more than this
     * by raising JVM settings and/or getting more PRAM or VRAM.
     * Therefore, ceiling = 20MB = 20 MB / .5 Kb = 40 k blocks
     * <P/>
     * TODO:  Supply a version of my MemTest program which people can run
     * one time when the server can be starved of RAM, and save the available
     * RAM quantity to a text file.  We can then really crank up the buffer
     * size to make transfers really efficient.
     * <P/>
     * We make the conservative simplification that each data file contains
     * just one huge data entry component.  This is a good estimation, since in
     * most cases, the contents of the single largest file will be many orders
     * of magnitude larger than the other files and the single block entry
     * headers.
     * <P/>
     * We aim for reading or writing these biggest file with 10 reads/writes.
     * In the case of READING Gzip files, there will actually be many more
     * reads than this, but that's the price you pay for smaller file size.
     *
     * @param files  Null array elements are permitted.  They will just be
     *               skipped by the algorithm.
     */
    static protected int generateBufferBlockValue(File[] files) {

        long maxFileSize = 0;

        for (int i = 0; i < files.length; i++) {
            if (files[i] == null) {
                continue;
            }

            if (files[i].length() > maxFileSize) {
                maxFileSize = files[i].length();
            }
        }

        int idealBlocks = (int) (maxFileSize / (10L * 512L));

        // I.e., 1/10 of the file, in units of 512 byte blocks.
        // It's fine that operations will truncate down instead of round.
        if (idealBlocks < 1) {
            return 1;
        }

        if (idealBlocks > 40 * 1024) {
            return 40 * 1024;
        }

        return idealBlocks;
    }

    /**
     * Convenience wrapper for generateBufferBlockValue(File[]).
     *
     * @see #generateBufferBlockValue(File[]).
     */
    static protected int generateBufferBlockValue(File file) {
        return generateBufferBlockValue(new File[]{ file });
    }
}
