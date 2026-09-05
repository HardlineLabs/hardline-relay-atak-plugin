package com.hardlinelabs.relay.atak.plugin;

import android.content.Context;
import android.widget.TextView;
import com.atak.plugins.impl.PluginContextProvider;
import com.hardlinelabs.relay.atak.StatusModel;
import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/** Minimal SDK-host entry point. Radio binding is deliberately not implemented yet. */
public final class RelayPlugin implements IPlugin {
    private final Context context;
    private final IHostUIService ui;
    private final ToolbarItem button;
    private Pane pane;

    public RelayPlugin(IServiceController services) {
        context = services.getService(PluginContextProvider.class).getPluginContext();
        ui = services.getService(IHostUIService.class);
        button = new ToolbarItem.Builder("Hardline Relay",
                MarshalManager.marshal(context.getDrawable(android.R.drawable.ic_menu_share),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setIdentifier("com.hardlinelabs.relay.atak.plugin")
                .setListener(new ToolbarItemAdapter() {
                    @Override public void onClick(ToolbarItem item) { showPane(); }
                }).build();
    }

    @Override public void onStart() { if (ui != null) ui.addToolbarItem(button); }

    @Override public void onStop() {
        if (ui != null) {
            if (pane != null) ui.closePane(pane);
            ui.removeToolbarItem(button);
        }
        pane = null;
    }

    private void showPane() {
        if (ui == null) return;
        if (pane == null) {
            TextView text = new TextView(context);
            text.setPadding(24, 24, 24, 24);
            text.setTextSize(18);
            text.setText("Hardline Relay — development scaffold\n\n"
                    + new StatusModel().meshLabel()
                    + "\nRadio transport is not wired yet. Normal TAK server state is independent.");
            pane = new PaneBuilder(text)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D).build();
        }
        if (!ui.isPaneVisible(pane)) ui.showPane(pane, null);
    }
}
