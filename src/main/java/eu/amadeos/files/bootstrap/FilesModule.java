package eu.amadeos.files.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import eu.amadeos.files.ui.main.MainPresenter;
import eu.amadeos.files.ui.main.MainView;
import eu.amadeos.files.ui.main.MainWindow;

/**
 * File Manager bindings. Executor, directories, theme and desktop launcher come from
 * {@link eu.amadeos.commons.app.CommonsModule}; the services are bound just in time.
 */
public final class FilesModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(MainWindow.class).in(Scopes.SINGLETON);
        bind(MainView.class).to(MainWindow.class);
        bind(MainPresenter.class).in(Scopes.SINGLETON);
    }
}
