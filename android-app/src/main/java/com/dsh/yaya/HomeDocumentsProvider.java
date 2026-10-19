package com.dsh.yaya;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * 用 SAF（Storage Access Framework）的 {@link DocumentsProvider}，把 App 内部
 * files/（= 引擎的 HOME，见 MainActivity 里 env HOME=getFilesDir()）挂成一个「根」，
 * 系统「文件」App 和任何支持 SAF 的文件管理器都能直接浏览、读写。
 *
 * 为什么要它：内部 files/ 是 /data/data/<包名>/files，普通文件管理器（含 MIUI 文件管理、
 * MT 管理器）在没有 root 的情况下完全看不到；而 MANAGE_EXTERNAL_STORAGE 只管
 * /sdcard，管不到这里。DocumentsProvider 是唯一不需要 root、不需要额外权限就能把
 * 内部目录交给系统文件管理器的正规通道（provider 端只由系统 DocumentsUI 调用）。
 *
 * 与 {@link LogShareProvider} 的分工（两者不重复、都需要）：
 *   · LogShareProvider —— exported=false，按 URI 逐个临时授权，只把**一个文件**递出去；
 *   · 本类 —— exported=true 但由 MANAGE_DOCUMENTS（signature|privileged）保护，
 *     常驻一个**可浏览的根**，只有系统能直连，第三方 App 需经用户授权目录树后才能读。
 *
 * 安全边界：
 *   · 只服务 getFilesDir() 之下的路径，docId 一律走 canonical 前缀校验
 *     （挡 ../ 与符号链接逃逸 —— payload 解压出来可能带软链）；
 *   · 凭据类文件名（{@link #HIDDEN_NAMES}）不列出、也不可打开；
 *   · 不暴露 files/ 的上级（父目录里有 databases/、shared_prefs/、app_webview/，
 *     那些没有交给文件管理器的必要）。
 *
 * ⚠️ 本类只是实现体，**必须同时在 AndroidManifest.xml 里声明 &lt;provider&gt;**
 *    才会出现在文件管理器的「根」列表里（见清单里的 .HomeDocumentsProvider）。
 */
public class HomeDocumentsProvider extends DocumentsProvider {

    private static final String TAG = "DeepSeekHarness";

    /**
     * authority = &lt;包名&gt;.documents.home。
     * 清单里现在写的是字面量 {@code com.dsh.yaya.documents.home}，两者必须一致
     * （与 LogShareProvider 同一套做法：字面量而非 ${applicationId}，因为装机包出的
     * 清单是原样搬运的，占位符不会有人替换）。Lite/兼容版出包时这里要跟着改包名前缀，
     * 否则三个版本会抢同一个 authority。
     */
    public static String authorityOf(Context ctx) {
        return ctx.getPackageName() + ".documents.home";
    }

    private static final String ROOT_ID = "harness_home";

    /**
     * 根的 docId。用 "/" 而不是 "root"，是为了和顶层真实文件不撞名：
     * 子文档 docId 是「相对 files/ 的路径」，顶层若真有个叫 root 的文件，
     * docId "root" 就会和根冲突。"/" 永远不可能出现在相对路径里。
     */
    private static final String DOC_ID_ROOT = "/";

    /**
     * 只读开关：改成 true 后文件管理器只能看，不再给 create/write/delete/rename
     * （同时根也去掉 FLAG_SUPPORTS_CREATE）。默认放开，便于直接改引擎配置、换插件。
     */
    private static final boolean READ_ONLY = false;

    /** 不通过 SAF 暴露的文件名（凭据）：不列出、不可打开。 */
    private static final Set<String> HIDDEN_NAMES = new HashSet<String>(Arrays.asList(
            ".credentials.yaml", ".credentials.yml", "credentials.yaml"));

    private static final String[] ROOT_COLUMNS = {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DOC_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
    };

    private File home;
    private String homeCanonical;

    @Override
    public boolean onCreate() {
        try {
            Context ctx = getContext();
            if (ctx == null) return false;
            home = ctx.getFilesDir();
            if (!home.isDirectory() && !home.mkdirs()) return false;
            homeCanonical = home.getCanonicalPath();
            Log.i(TAG, "HomeDocumentsProvider ready: " + homeCanonical);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "HomeDocumentsProvider init failed", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 根

    /** 三版本共存时根名字要能区分（与 MainActivity#pkgRoot() 的取名规则一致）。 */
    private String rootTitle() {
        Context ctx = getContext();
        String p = ctx == null ? "" : ctx.getPackageName();
        if (p.contains("beta")) return "DeepSeek Harness Lite";
        if (p.contains("compat")) return "DeepSeek Harness Compat";
        return "DeepSeek Harness";
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(use(projection, ROOT_COLUMNS));
        int rootFlags = DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                | (READ_ONLY ? 0 : DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
        String pkg = "";
        try {
            Context ctx = getContext();
            if (ctx != null) pkg = ctx.getPackageName();
        } catch (Throwable ignored) {
        }
        String[] cols = c.getColumnNames();
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            String k = cols[i];
            if (DocumentsContract.Root.COLUMN_ROOT_ID.equals(k)) row[i] = ROOT_ID;
            else if (DocumentsContract.Root.COLUMN_DOCUMENT_ID.equals(k)) row[i] = DOC_ID_ROOT;
            else if (DocumentsContract.Root.COLUMN_TITLE.equals(k)) row[i] = rootTitle();
            else if (DocumentsContract.Root.COLUMN_SUMMARY.equals(k)) row[i] = homeCanonical;
            else if (DocumentsContract.Root.COLUMN_MIME_TYPES.equals(k)) row[i] = "*/*";
            else if (DocumentsContract.Root.COLUMN_ICON.equals(k)) {
                row[i] = Integer.valueOf(android.R.drawable.ic_menu_manage);
            } else if (DocumentsContract.Root.COLUMN_FLAGS.equals(k)) {
                row[i] = Integer.valueOf(rootFlags);
            } else if (DocumentsContract.Root.COLUMN_AVAILABLE_BYTES.equals(k)) {
                row[i] = Long.valueOf(home.getUsableSpace());
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        Log.i(TAG, "queryRoots pkg=" + pkg + " title=" + rootTitle() + " root=" + homeCanonical);
        return c;
    }

    // -------------------------------------------------------------- 文档读取

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(use(projection, DOC_COLUMNS));
        File f = fileForDocId(documentId);
        includeDocument(c, docIdFor(f), f);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(use(projection, DOC_COLUMNS));
        File parent = fileForDocId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("not a directory: " + parentDocumentId);
        }
        File[] kids = parent.listFiles();
        if (kids != null) {
            Arrays.sort(kids, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    // 目录在前，再按名字（与系统文件管理器观感一致）
                    boolean da = a.isDirectory();
                    boolean db = b.isDirectory();
                    if (da != db) return da ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File k : kids) {
                if (HIDDEN_NAMES.contains(k.getName())) continue;
                try {
                    includeDocument(c, docIdFor(k), k);
                } catch (FileNotFoundException ignored) {
                    // 列目录与取 docId 之间文件被删/被移出边界：跳过这一条，别让整个目录失败
                }
            }
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File f = fileForDocId(documentId);
        if (f.isDirectory()) throw new FileNotFoundException("is a directory: " + documentId);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    // -------------------------------------------------------------- 写操作

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (READ_ONLY) throw new FileNotFoundException("read-only provider");
        File parent = fileForDocId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("not a directory: " + parentDocumentId);
        }
        String name = safeName(displayName);
        if (name == null || HIDDEN_NAMES.contains(name)) {
            throw new FileNotFoundException("bad name: " + displayName);
        }
        File target = ensureInside(parent, new File(parent, name));
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            if (!target.mkdirs()) throw new FileNotFoundException("mkdir failed: " + name);
        } else {
            try {
                if (!target.createNewFile()) throw new FileNotFoundException("exists: " + name);
            } catch (IOException e) {
                throw new FileNotFoundException("create failed: " + name);
            }
        }
        return docIdFor(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (READ_ONLY) throw new FileNotFoundException("read-only provider");
        File f = fileForDocId(documentId);
        if (DOC_ID_ROOT.equals(documentId)) throw new FileNotFoundException("cannot delete root");
        if (!deleteRecursively(f)) throw new FileNotFoundException("delete failed: " + documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (READ_ONLY) throw new FileNotFoundException("read-only provider");
        if (DOC_ID_ROOT.equals(documentId)) throw new FileNotFoundException("cannot rename root");
        File src = fileForDocId(documentId);
        String name = safeName(displayName);
        if (name == null || HIDDEN_NAMES.contains(name)) {
            throw new FileNotFoundException("bad name: " + displayName);
        }
        File parent = src.getParentFile();
        if (parent == null) throw new FileNotFoundException("no parent: " + documentId);
        File dst = ensureInside(parent, new File(parent, name));
        if (!src.renameTo(dst)) throw new FileNotFoundException("rename failed: " + documentId);
        return docIdFor(dst);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String childDocumentId) {
        try {
            String p = fileForDocId(parentDocumentId).getCanonicalPath();
            String c = fileForDocId(childDocumentId).getCanonicalPath();
            return c.equals(p) || c.startsWith(p + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 内部

    private static String[] use(String[] projection, String[] fallback) {
        return (projection != null && projection.length > 0) ? projection : fallback;
    }

    /** docId → File，绝不允许逃出 files/。 */
    private File fileForDocId(String documentId) throws FileNotFoundException {
        if (documentId == null || documentId.length() == 0) {
            throw new FileNotFoundException("empty documentId");
        }
        if (DOC_ID_ROOT.equals(documentId)) return home;
        String rel = documentId.startsWith("/") ? documentId.substring(1) : documentId;
        // 先做纯字符串层拦截（后面对 canonical 再做一次前缀校验，双保险）
        if (rel.indexOf('\0') >= 0 || rel.indexOf('\\') >= 0 || rel.contains("..")) {
            throw new FileNotFoundException("bad documentId: " + documentId);
        }
        File f = ensureInside(home, new File(home, rel));
        if (HIDDEN_NAMES.contains(f.getName())) {
            throw new FileNotFoundException("hidden: " + documentId);
        }
        return f;
    }

    /** File → docId（相对 files/ 的路径；根为 "/"）。 */
    private String docIdFor(File f) throws FileNotFoundException {
        String c = canonical(f);
        if (c == null) throw new FileNotFoundException("cannot resolve: " + f);
        if (c.equals(homeCanonical)) return DOC_ID_ROOT;
        if (!c.startsWith(homeCanonical + File.separator)) {
            throw new FileNotFoundException("outside home: " + f);
        }
        return c.substring(homeCanonical.length() + 1);
    }

    /** canonical 前缀校验：挡 ../ 与符号链接逃逸。 */
    private static File ensureInside(File parent, File child) throws FileNotFoundException {
        String pc = canonical(parent);
        String cc = canonical(child);
        if (pc == null || cc == null) throw new FileNotFoundException("cannot resolve: " + child);
        if (!cc.startsWith(pc + File.separator)) {
            throw new FileNotFoundException("escapes parent: " + child);
        }
        return new File(cc);
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 按**列名**（而不是固定顺序）填一行 —— 调用方给的 projection 顺序和内容都不保证，
     * 按位置硬填会把 displayName/size 串位。
     */
    private void includeDocument(MatrixCursor result, String documentId, File f)
            throws FileNotFoundException {
        if (!f.exists()) throw new FileNotFoundException(documentId);
        boolean dir = f.isDirectory();
        String mime = dir ? DocumentsContract.Document.MIME_TYPE_DIR : mimeOf(f);
        long size = dir ? 0L : f.length();
        int flags = 0;
        boolean writable = !READ_ONLY && f.canWrite();
        if (dir && writable) flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        if (!DOC_ID_ROOT.equals(documentId) && writable) {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                    | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
            if (!dir) flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        }
        if (!dir) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".webp") || n.endsWith(".gif")) {
                flags |= DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL;
            }
        }
        String[] cols = result.getColumnNames();
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            String k = cols[i];
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(k)) row[i] = documentId;
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(k)) row[i] = mime;
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(k)) row[i] = f.getName();
            else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(k)) {
                row[i] = Long.valueOf(f.lastModified());
            } else if (DocumentsContract.Document.COLUMN_FLAGS.equals(k)) {
                row[i] = Integer.valueOf(flags);
            } else if (DocumentsContract.Document.COLUMN_SIZE.equals(k)) {
                row[i] = Long.valueOf(size);
            } else {
                row[i] = null;
            }
        }
        result.addRow(row);
    }

    /** 复用 LogShareProvider 的 MIME 表（已含 docx/apk/md/js 等），不另起一份。 */
    private static String mimeOf(File f) {
        String m = LogShareProvider.mimeOfName(f.getName());
        return "*/*".equals(m) ? "application/octet-stream" : m;
    }

    /** 取纯文件名：拒绝空、'/'、'\\'、'\0' 与 ".."，挡住拼接出的穿越名。 */
    private static String safeName(String displayName) {
        if (displayName == null) return null;
        String n = displayName.trim();
        if (n.length() == 0 || n.length() > 255) return null;
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0) return null;
        if (n.equals(".") || n.equals("..")) return null;
        return n;
    }

    private static boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!deleteRecursively(k)) return false;
                }
            }
        }
        return f.delete();
    }
}
