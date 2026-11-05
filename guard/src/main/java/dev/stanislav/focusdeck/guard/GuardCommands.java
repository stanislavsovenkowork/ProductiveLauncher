package dev.stanislav.focusdeck.guard;

/** Internal dispatcher. Its only exported entry point is the DUMP-protected receiver. */
final class GuardCommands {
    static final String ACTIVATE = "dev.stanislav.focusdeck.guard.ACTIVATE";
    static final String STATUS = "dev.stanislav.focusdeck.guard.STATUS";
    static final String RECOVER = "dev.stanislav.focusdeck.guard.RECOVER";

    // Deliberately accepts no packages, features, baseline, user ID, credentials or file paths.
    static GuardController.Result dispatch(GuardController controller, String action) {
        if (ACTIVATE.equals(action)) return controller.activate(true);
        if (STATUS.equals(action)) return controller.status();
        if (RECOVER.equals(action)) return controller.recover();
        return new GuardController.Result(GuardController.Code.REFUSED, "Unsupported administrative operation.");
    }

    private GuardCommands() {}
}
