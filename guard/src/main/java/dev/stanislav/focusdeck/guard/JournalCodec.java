package dev.stanislav.focusdeck.guard;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.TreeSet;

/** Versioned, bounded record; contains no credential, token, account or vault data. */
final class JournalCodec {
    private static final int MAGIC = 0x46444731;
    static GuardController.Journal read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC) throw new IOException("Unknown journal format");
        int phase = in.readInt();
        if (phase < 0 || phase >= GuardController.Phase.values().length) throw new IOException("Invalid phase");
        int features = in.readInt();
        int count = in.readInt();
        if (count < 0 || count > 128) throw new IOException("Invalid package count");
        Set<String> packages = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            if (name.isEmpty() || name.length() > 255 || !packages.add(name)) throw new IOException("Invalid package");
        }
        if (in.read() != -1) throw new IOException("Unexpected journal data");
        return new GuardController.Journal(new GuardController.Policy(packages, features),
                GuardController.Phase.values()[phase]);
    }
    static void write(GuardController.Journal journal, OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeInt(journal.phase().ordinal());
        out.writeInt(journal.before().features());
        out.writeInt(journal.before().packages().size());
        for (String name : new TreeSet<>(journal.before().packages())) out.writeUTF(name);
        out.flush();
    }
    private JournalCodec() {}
}
