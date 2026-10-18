package eu.amadeos.apps.files.ui.main;

import eu.amadeos.apps.files.domain.Progress;

/**
 * The progress dialog of a running operation. Called on the EDT.
 */
public interface ProgressView {
    void update(Progress progress);

    void close();
}
