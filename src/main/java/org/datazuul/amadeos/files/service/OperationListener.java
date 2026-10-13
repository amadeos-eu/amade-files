package org.datazuul.amadeos.files.service;

import org.datazuul.amadeos.files.domain.Progress;
import org.datazuul.amadeos.files.domain.Resolution;

import java.nio.file.Path;

/**
 * Follows a running operation. Called on the worker thread.
 */
public interface OperationListener {
    void progress(Progress progress);

    /** The target exists; may block while the user decides. */
    Resolution conflict(Path source, Path target);

    /** Checked between files and between blocks of large files. */
    boolean isCancelled();

    /** Never asks and never cancels; overwrites nothing. */
    OperationListener SKIP_CONFLICTS = new OperationListener() {
        @Override
        public void progress(Progress progress) {
        }

        @Override
        public Resolution conflict(Path source, Path target) {
            return Resolution.SKIP;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };
}
