package su.nightexpress.excellentcrates.dialog.crate;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.dialog.Dialog;
import su.nightexpress.nightcore.bridge.dialog.wrap.WrappedDialog;
import su.nightexpress.nightcore.locale.LangEntry;
import su.nightexpress.nightcore.locale.entry.DialogElementLocale;
import su.nightexpress.nightcore.locale.entry.TextLocale;
import su.nightexpress.nightcore.ui.dialog.Dialogs;
import su.nightexpress.nightcore.ui.dialog.build.*;

import static su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers.*;

public class CrateRerollDialog extends Dialog<Crate> {

    private static final TextLocale TITLE = LangEntry.builder("Dialog.Crate.Reroll.Title").text(title("Crate", "Reward Reroll"));

    private static final DialogElementLocale BODY = LangEntry.builder("Dialog.Crate.Reroll.Body").dialogElement(400,
        "Here you can enable reward confirmation with rerolls.",
        "",
        SOFT_YELLOW.wrap("Attempts") + " sets how many times a player can reroll the reward after the crate opening animation finishes.",
        "",
        "Rerolls do not charge the opening cost again.",
        "",
        SOFT_YELLOW.wrap("→") + " To disable this feature, uncheck the " + SOFT_YELLOW.wrap("Enabled") + " box."
    );

    private static final TextLocale INPUT_ENABLED  = LangEntry.builder("Dialog.Crate.Reroll.Input.Enabled").text("Enabled");
    private static final TextLocale INPUT_ATTEMPTS = LangEntry.builder("Dialog.Crate.Reroll.Input.Attempts").text("Attempts " + GRAY.wrap("(min. 0)"));

    private static final String JSON_ENABLED  = "enabled";
    private static final String JSON_ATTEMPTS = "attempts";

    @Override
    @NotNull
    public WrappedDialog create(@NotNull Player player, @NotNull Crate crate) {
        return Dialogs.create(builder -> {
            builder.base(DialogBases.builder(TITLE)
                .body(DialogBodies.plainMessage(BODY))
                .inputs(
                    DialogInputs.bool(JSON_ENABLED, INPUT_ENABLED).initial(crate.isRerollEnabled()).build(),
                    DialogInputs.text(JSON_ATTEMPTS, INPUT_ATTEMPTS).initial(String.valueOf(crate.getRerollAttempts())).build()
                )
                .build()
            );

            builder.type(DialogTypes.multiAction(DialogButtons.ok()).exitAction(DialogButtons.back()).build());

            builder.handleResponse(DialogActions.OK, (viewer, identifier, nbtHolder) -> {
                if (nbtHolder == null) return;

                boolean enabled = nbtHolder.getBoolean(JSON_ENABLED, crate.isRerollEnabled());
                int attempts = nbtHolder.getInt(JSON_ATTEMPTS, crate.getRerollAttempts());

                crate.setRerollEnabled(enabled);
                crate.setRerollAttempts(attempts);
                crate.markDirty();
                viewer.callback();
            });
        });
    }
}
