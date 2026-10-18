package eu.amadeos.apps.files.bootstrap;

import eu.amadeos.commons.app.AmadeApplication;
import eu.amadeos.commons.app.AppInfo;
import eu.amadeos.apps.files.ui.main.MainPresenter;
import eu.amadeos.apps.files.ui.main.MainWindow;

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
