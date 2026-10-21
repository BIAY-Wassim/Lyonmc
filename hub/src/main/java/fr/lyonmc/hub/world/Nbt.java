package fr.lyonmc.hub.world;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Petit lecteur NBT (fichiers .schem compressés), indépendant de la version du serveur. */
final class Nbt {

    private Nbt() {
    }

    /** Lit un fichier NBT compressé ; renvoie le compound racine. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(File file) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(file)));
             DataInputStream data = new DataInputStream(new BufferedInputStream(in))) {
            int type = data.readByte();
            if (type != 10) throw new IOException("Ce fichier n'est pas un schematic valide");
            data.readUTF();
            return (Map<String, Object>) payload(data, 10, 0);
        }
    }

    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("Fichier trop imbriqué");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] b = new byte[checkedLength(in.readInt())];
                in.readFully(b);
                return b;
            }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readByte();
                int n = checkedLength(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) list.add(payload(in, t, depth + 1));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) return map;
                    String name = in.readUTF();
                    map.put(name, payload(in, t, depth + 1));
                }
            }
            case 11: {
                int[] a = new int[checkedLength(in.readInt())];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[checkedLength(in.readInt())];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default:
                throw new IOException("Type NBT inconnu : " + type);
        }
    }

    private static int checkedLength(int n) throws IOException {
        if (n < 0 || n > 64_000_000) throw new IOException("Taille invalide dans le fichier");
        return n;
    }
}
