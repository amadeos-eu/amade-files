package eu.amadeos.files.ui.main;

import eu.amadeos.files.domain.Progress;

/**
 * The progress dialog of a running operation. Called on the EDT.
 */
public interface ProgressView {
    void update(Progress progress);

    void close();
}
