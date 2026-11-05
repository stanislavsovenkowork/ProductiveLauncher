package dev.stanislav.focusdeck.guard;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

final class AndroidJournalStore implements GuardController.Store {
    private final AtomicFile file;
    AndroidJournalStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "lock-task-journal.bin"));
    }
    @Override public GuardController.Journal read() throws IOException {
        try (FileInputStream stream = file.openRead()) {
            return JournalCodec.read(stream);
        } catch (FileNotFoundException failure) {
            // openRead first restores a legacy .bak. Do not confuse an unreadable or
            // interrupted record with a first installation that has never written one.
            File base = file.getBaseFile();
            if (base.exists() || new File(base.getPath() + ".bak").exists()
                    || new File(base.getPath() + ".new").exists()) throw failure;
            return null;
        }
    }
    @Override public void write(GuardController.Journal journal) throws IOException {
        FileOutputStream stream = file.startWrite();
        try {
            JournalCodec.write(journal, stream);
            stream.getFD().sync();
            file.finishWrite(stream);
            // AtomicFile can report some filesystem failures only through internal logs.
            if (!journal.equals(read())) throw new IOException("Journal read-back failed");
        } catch (IOException | RuntimeException failure) {
            file.failWrite(stream);
            throw failure;
        }
    }
}
