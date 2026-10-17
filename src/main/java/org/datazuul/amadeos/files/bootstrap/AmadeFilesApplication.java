package org.datazuul.amadeos.files.bootstrap;

import org.datazuul.amadeos.commons.app.AmadeApplication;
import org.datazuul.amadeos.commons.app.AppInfo;
import org.datazuul.amadeos.files.ui.main.MainPresenter;
import org.datazuul.amadeos.files.ui.main.MainWindow;

import java.util.List;

/**
 * Entry point. Arguments are folders (or files) for the left and right panel, as passed by the
 * desktop entry; {@code trash:} opens the trash, as the front panel's Trash Can does.
 */
public final class AmadeFilesApplication {
    public static final AppInfo APP = new AppInfo("amade-files", "File Manager", "files");

    private AmadeFilesApplication() {
    }

    public static void main(String[] args) {
        AmadeApplication.launch(APP, injector -> {
            MainWindow window = injector.getInstance(MainWindow.class);
            MainPresenter presenter = injector.getInstance(MainPresenter.class);
            window.bind(presenter);
            window.setVisible(true);
            presenter.start(List.of(args));
        }, new FilesModule());
    }
}
