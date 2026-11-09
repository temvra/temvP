package com.temvra.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Bóveda en una carpeta real del móvil, elegida con el selector de carpetas del sistema (Storage Access Framework):
 * no necesita permisos de almacenamiento y Android recuerda el acceso. Las rutas son relativas, con «/».
 * Lo que empieza por punto (.obsidian, .trash, .git…) no se lista. Borrar es mover a .trash, como hace Obsidian.
 */
final class VaultDir {
    private VaultDir() {}

    private static final String[] COLS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE};
    /** Con este tipo el proveedor respeta el nombre tal cual (con otro tipo podría añadir su propia extensión). */
    private static final String OCTET = "application/octet-stream";
    /** Id de documento de cada ruta, recordado del último listado para no recorrer la carpeta en cada lectura. */
    private static final Map<String, String> IDS = new HashMap<>();
    /** Cuándo se listó cada carpeta: justo después, lo que no salió en el listado no existe y se crea sin buscarlo antes. */
    private static final Map<String, Long> LISTED = new HashMap<>();

    private static String key(Uri tree, String path) { return tree + "|" + path; }
    private static String rootId(Uri tree) { return DocumentsContract.getTreeDocumentId(tree); }
    private static Uri doc(Uri tree, String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }
    private static String cached(Uri tree, String path) { synchronized (IDS) { return IDS.get(key(tree, path)); } }
    private static void remember(Uri tree, String path, String id) { synchronized (IDS) { IDS.put(key(tree, path), id); } }
    /** Lo oculto (.trash…) no sale en el listado: ahí siempre se busca. */
    private static boolean known(Uri tree, String path) {
        if (("/" + path).contains("/.")) return false;
        synchronized (IDS) { Long t = LISTED.get(tree.toString()); return t != null && System.currentTimeMillis() - t < 120000; }
    }

    /** Nombre con el que quedó un documento: si ya había otro igual, el proveedor le pone otro («Nota (1).md»). */
    private static String nameOf(Context c, Uri tree, String id) {
        try (Cursor cur = c.getContentResolver().query(doc(tree, id), new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    /** Crea un documento con ese nombre exacto. Si ya había otro igual (el proveedor lo llamaría «Nota (1).md»), lo deshace y devuelve null. */
    private static String create(Context c, Uri tree, String parentId, String mime, String name) throws Exception {
        ContentResolver cr = c.getContentResolver();
        Uri u = DocumentsContract.createDocument(cr, doc(tree, parentId), mime, name);
        if (u == null) throw new Exception("No se pudo crear " + name);
        String id = DocumentsContract.getDocumentId(u), got = nameOf(c, tree, id);
        if (got == null || got.equals(name)) return id;
        try { DocumentsContract.deleteDocument(cr, u); } catch (Exception ignored) {}
        return null;
    }

    /** Nombre de la carpeta elegida, para enseñarlo en la app. */
    static String name(Context c, Uri tree) {
        try (Cursor cur = c.getContentResolver().query(doc(tree, rootId(tree)), new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (Exception ignored) {}
        String id = rootId(tree);
        int k = id.lastIndexOf('/');
        if (k >= 0) return id.substring(k + 1);
        k = id.indexOf(':');
        return k >= 0 && k < id.length() - 1 ? id.substring(k + 1) : id;
    }

    /** Todos los archivos y carpetas (sin los ocultos), con fecha y tamaño. */
    static JSONArray list(Context c, Uri tree) throws Exception {
        JSONArray out = new JSONArray();
        String pre = tree + "|";
        synchronized (IDS) { IDS.keySet().removeIf(k -> k.startsWith(pre)); LISTED.remove(tree.toString()); }
        walk(c, tree, rootId(tree), "", out, 0);
        synchronized (IDS) { LISTED.put(tree.toString(), System.currentTimeMillis()); }
        return out;
    }

    private static void walk(Context c, Uri tree, String id, String prefix, JSONArray out, int depth) throws Exception {
        if (depth > 32) return;
        try (Cursor cur = c.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), COLS, null, null, null)) {
            if (cur == null) {
                if (depth == 0) throw new Exception("No se pudo leer la carpeta");
                return;
            }
            while (cur.moveToNext()) {
                String did = cur.getString(0), nm = cur.getString(1), mime = cur.getString(2);
                if (did == null || nm == null || nm.startsWith(".")) continue;
                boolean dir = Document.MIME_TYPE_DIR.equals(mime);
                String p = prefix + nm;
                synchronized (IDS) { IDS.put(key(tree, p), did); }
                JSONObject o = new JSONObject();
                o.put("path", p);
                o.put("dir", dir);
                if (!dir) {
                    o.put("mtime", cur.isNull(3) ? 0 : cur.getLong(3));
                    o.put("size", cur.isNull(4) ? 0 : cur.getLong(4));
                }
                out.put(o);
                if (dir) walk(c, tree, did, p + "/", out, depth + 1);
            }
        }
    }

    /** Hijo con ese nombre dentro de la carpeta parentId, o null. */
    private static String child(Context c, Uri tree, String parentId, String name) {
        try (Cursor cur = c.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) if (name.equals(cur.getString(1))) return cur.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    /** Id de la ruta (archivo o carpeta) o null si no existe. */
    static String find(Context c, Uri tree, String path) {
        String id = rootId(tree);
        if (path == null) return id;
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            id = child(c, tree, id, part);
            if (id == null) return null;
        }
        return id;
    }

    private static String idOf(Context c, Uri tree, String path) {
        String id;
        synchronized (IDS) { id = IDS.get(key(tree, path)); }
        return id != null ? id : find(c, tree, path);
    }

    /** Crea las carpetas que falten y devuelve el id de la última. */
    static String ensureDir(Context c, Uri tree, String path) throws Exception {
        String id = rootId(tree);
        if (path == null) return id;
        String acc = "";
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (part.equals("..")) throw new Exception("Ruta no válida");
            acc = acc.isEmpty() ? part : acc + "/" + part;
            String nx = cached(tree, acc);
            if (nx == null && !known(tree, acc)) nx = child(c, tree, id, part);
            if (nx == null && (nx = create(c, tree, id, Document.MIME_TYPE_DIR, part)) == null && (nx = child(c, tree, id, part)) == null)
                throw new Exception("Nombre no válido en esta carpeta: " + part);
            remember(tree, acc, nx);
            id = nx;
        }
        return id;
    }

    private static byte[] bytes(Context c, Uri u) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("No se pudo abrir el archivo");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toByteArray();
        }
    }

    static String read(Context c, Uri tree, String path, boolean bin) throws Exception {
        String id = idOf(c, tree, path);
        if (id == null) throw new Exception("No existe " + path);
        byte[] b;
        try {
            b = bytes(c, doc(tree, id));
        } catch (Exception e) {
            // El id recordado puede haber caducado (otra app movió el archivo): se busca otra vez
            id = find(c, tree, path);
            if (id == null) throw e;
            b = bytes(c, doc(tree, id));
        }
        return bin ? Base64.encodeToString(b, Base64.NO_WRAP) : new String(b, StandardCharsets.UTF_8);
    }

    private static JSONObject stat(Context c, Uri tree, String id) throws Exception {
        JSONObject o = new JSONObject();
        try (Cursor cur = c.getContentResolver().query(doc(tree, id), COLS, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                o.put("mtime", cur.isNull(3) ? 0 : cur.getLong(3));
                o.put("size", cur.isNull(4) ? 0 : cur.getLong(4));
            }
        }
        return o;
    }

    /** Escribe el archivo (creándolo con sus carpetas si hace falta) y devuelve su id. */
    private static String put(Context c, Uri tree, String path, byte[] b) throws Exception {
        int k = path.lastIndexOf('/');
        String dir = k < 0 ? "" : path.substring(0, k), nm = path.substring(k + 1);
        if (nm.isEmpty() || nm.equals("..")) throw new Exception("Ruta no válida");
        ContentResolver cr = c.getContentResolver();
        // Con el listado recién hecho no hace falta recorrer la carpeta para cada archivo (la primera vez puede haber cientos)
        String pid = ensureDir(c, tree, dir), id = cached(tree, path);
        if (id == null && !known(tree, path)) id = child(c, tree, pid, nm);
        boolean fresh = false;
        if (id == null) {
            id = create(c, tree, pid, OCTET, nm);
            fresh = id != null;
            // Apareció después del listado: se sobrescribe ese («wt» la vacía antes)
            if (id == null && (id = child(c, tree, pid, nm)) == null) throw new Exception("Nombre no válido en esta carpeta: " + nm);
        }
        Uri u = doc(tree, id);
        try (OutputStream o = cr.openOutputStream(u, fresh ? "w" : "wt")) {
            if (o == null) throw new Exception("No se pudo escribir " + nm);
            o.write(b);
        } catch (Exception e) {
            if (fresh) throw e;
            // Algún proveedor no sabe truncar («wt»): se escribe en un archivo nuevo y solo entonces se sustituye el viejo
            Uri t = DocumentsContract.createDocument(cr, doc(tree, pid), OCTET, "." + nm + ".temvra-tmp");
            if (t == null) throw e;
            try (OutputStream o = cr.openOutputStream(t, "w")) {
                if (o == null) throw e;
                o.write(b);
            }
            DocumentsContract.deleteDocument(cr, u);
            Uri r = DocumentsContract.renameDocument(cr, t, nm);
            u = r != null ? r : t;
            id = DocumentsContract.getDocumentId(u);
        }
        synchronized (IDS) { IDS.put(key(tree, path), id); }
        return id;
    }

    static JSONObject write(Context c, Uri tree, String path, String data, boolean bin) throws Exception {
        String s = data == null ? "" : data;
        byte[] b = bin ? Base64.decode(s, Base64.DEFAULT) : s.getBytes(StandardCharsets.UTF_8);
        return stat(c, tree, put(c, tree, path, b));
    }

    /** Mueve el archivo a .trash (con sus carpetas), como la papelera de Obsidian: se puede recuperar. */
    static void trash(Context c, Uri tree, String path) throws Exception {
        String id = idOf(c, tree, path);
        if (id == null) return;
        byte[] b;
        try {
            b = bytes(c, doc(tree, id));
        } catch (Exception e) {
            id = find(c, tree, path);
            if (id == null) return;
            b = bytes(c, doc(tree, id));
        }
        ContentResolver cr = c.getContentResolver();
        int k = path.lastIndexOf('/');
        String dir = ".trash" + (k < 0 ? "" : "/" + path.substring(0, k)), nm = path.substring(k + 1);
        // Si en la papelera ya hay uno con ese nombre, el proveedor le añade « (1)»
        Uri t = DocumentsContract.createDocument(cr, doc(tree, ensureDir(c, tree, dir)), OCTET, nm);
        if (t == null) throw new Exception("No se pudo mover a la papelera");
        try (OutputStream o = cr.openOutputStream(t, "w")) {
            if (o == null) throw new Exception("No se pudo mover a la papelera");
            o.write(b);
        }
        DocumentsContract.deleteDocument(cr, doc(tree, id));
        synchronized (IDS) { IDS.remove(key(tree, path)); }
    }

    /** Renombra o mueve un archivo dentro de la carpeta. */
    static JSONObject move(Context c, Uri tree, String from, String to) throws Exception {
        String id = idOf(c, tree, from);
        if (id == null) throw new Exception("No existe " + from);
        if (find(c, tree, to) != null) throw new Exception("Ya existe " + to);
        ContentResolver cr = c.getContentResolver();
        int a = from.lastIndexOf('/'), b = to.lastIndexOf('/');
        String fd = a < 0 ? "" : from.substring(0, a), td = b < 0 ? "" : to.substring(0, b), tn = to.substring(b + 1);
        String nid;
        if (fd.equals(td)) {
            Uri r = DocumentsContract.renameDocument(cr, doc(tree, id), tn);
            nid = r != null ? DocumentsContract.getDocumentId(r) : id;
        } else {
            nid = put(c, tree, to, bytes(c, doc(tree, id)));
            DocumentsContract.deleteDocument(cr, doc(tree, id));
        }
        synchronized (IDS) {
            IDS.remove(key(tree, from));
            IDS.put(key(tree, to), nid);
        }
        return stat(c, tree, nid);
    }
}
