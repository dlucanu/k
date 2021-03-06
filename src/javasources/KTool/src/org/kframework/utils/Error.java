// Copyright (c) 2012-2014 K Team. All Rights Reserved.
package org.kframework.utils;

import java.io.File;

import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import java.util.Comparator;

import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.errorsystem.KException.ExceptionType;
import org.kframework.utils.errorsystem.KException.KExceptionGroup;
import org.kframework.utils.general.GlobalSettings;

/**
 * @deprecated Use {@link KExceptionManager} instead.
 */
@Deprecated
public class Error {

    /**
     * @deprecated Use {@link KExceptionManager} instead.
     */
    @Deprecated
    public static void report(String message) {
        GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, message));
    }

    /**
     * @deprecated Use {@link KExceptionManager} instead.
     */
    @Deprecated
    public static void silentReport(String localizedMessage) {
        System.out.println("Warning: " + localizedMessage);
    }

    /**
     * @deprecated Use {@link KExceptionManager} instead.
     */
    @Deprecated
    public static void helpMsg(String usage, String header, String footer, Options options, Comparator comparator) {
        HelpFormatter helpFormatter = new HelpFormatter();
        helpFormatter.setOptionComparator(comparator);
        helpFormatter.setWidth(79);
        helpFormatter.printHelp(usage, header, options, footer);
        System.out.println();
        //System.exit(1);
    }

    public static void checkIfInputDirectory(String directory) {
        if (!new File(directory).isDirectory()) {
            String msg = "Does not exist or not a directory: " + directory;
            GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, msg, "", ""));
        }
    }
    public static void checkIfOutputDirectory(File directory) {
        if (directory.isFile()) { // isFile = exists && !isDirectory
            String msg = "Not a directory: " + directory;
            GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, msg, "", ""));
        }
    }
}

// vim: noexpandtab
