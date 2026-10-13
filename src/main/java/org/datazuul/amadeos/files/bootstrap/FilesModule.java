package org.datazuul.amadeos.files.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import org.datazuul.amadeos.files.ui.main.MainPresenter;
import org.datazuul.amadeos.files.ui.main.MainView;
import org.datazuul.amadeos.files.ui.main.MainWindow;

/**
 * File Manager bindings. Executor, directories, theme and desktop launcher come from
 * {@link org.datazuul.amadeos.commons.app.CommonsModule}; the services are bound just in time.
 */
public final class FilesModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(MainWindow.class).in(Scopes.SINGLETON);
        bind(MainView.class).to(MainWindow.class);
        bind(MainPresenter.class).in(Scopes.SINGLETON);
    }
}
