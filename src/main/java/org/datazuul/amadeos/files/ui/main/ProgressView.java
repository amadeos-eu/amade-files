package org.datazuul.amadeos.files.ui.main;

import org.datazuul.amadeos.files.domain.Progress;

/**
 * The progress dialog of a running operation. Called on the EDT.
 */
public interface ProgressView {
    void update(Progress progress);

    void close();
}
