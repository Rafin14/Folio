package dev.folio.scanner;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;

/** Real seekable SAF files in the test APK, including controlled one-shot I/O failure. */
public class UtilityTestStorage extends DocumentsProvider {
    private File base() { File f=new File(getContext().getFilesDir(),"pdf-test-storage"); f.mkdirs(); try { return f.getCanonicalFile(); } catch(java.io.IOException e) { throw new IllegalStateException(e); } }
    private File file(String id) throws FileNotFoundException { try { File f=id.equals("root")?base():new File(base(),id).getCanonicalFile(); if(!f.toPath().startsWith(base().getCanonicalFile().toPath())) throw new FileNotFoundException(); return f; } catch(java.io.IOException e) { throw new FileNotFoundException(id); } }
    public boolean onCreate() { return true; }
    public Cursor queryRoots(String[] projection) { MatrixCursor c=new MatrixCursor(projection!=null?projection:new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES}); Object[] row=new Object[c.getColumnCount()]; for(int i=0;i<row.length;i++) { String s=c.getColumnName(i); if(s.equals(Root.COLUMN_ROOT_ID)||s.equals(Root.COLUMN_DOCUMENT_ID)) row[i]="root"; else if(s.equals(Root.COLUMN_TITLE)) row[i]="Folio test files"; else if(s.equals(Root.COLUMN_FLAGS)) row[i]=Root.FLAG_SUPPORTS_CREATE|Root.FLAG_LOCAL_ONLY; else if(s.equals(Root.COLUMN_MIME_TYPES)) row[i]="*/*"; } c.addRow(row); return c; }
    private MatrixCursor cursor(String[] projection) { return new MatrixCursor(projection!=null?projection:new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE}); }
    private String id(File f) { return f.equals(base())?"root":base().toPath().relativize(f.toPath()).toString(); }
    private void add(MatrixCursor c,File f) { Object[] row=new Object[c.getColumnCount()]; for(int i=0;i<row.length;i++) { String s=c.getColumnName(i); if(s.equals(Document.COLUMN_DOCUMENT_ID)) row[i]=id(f); else if(s.equals(Document.COLUMN_DISPLAY_NAME)) row[i]=f.getName(); else if(s.equals(Document.COLUMN_MIME_TYPE)) row[i]=f.isDirectory()?Document.MIME_TYPE_DIR:f.getName().endsWith("pdf")?"application/pdf":"image/png"; else if(s.equals(Document.COLUMN_SIZE)) row[i]=f.length(); else if(s.equals(Document.COLUMN_FLAGS)) row[i]=f.isDirectory()?Document.FLAG_DIR_SUPPORTS_CREATE:Document.FLAG_SUPPORTS_WRITE|Document.FLAG_SUPPORTS_DELETE; } c.addRow(row); }
    public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException { MatrixCursor c=cursor(projection); File f=file(id); if(!f.exists()) throw new FileNotFoundException(id); add(c,f); return c; }
    public Cursor queryChildDocuments(String id,String[] projection,String sort) throws FileNotFoundException { MatrixCursor c=cursor(projection); File[] files=file(id).listFiles(); if(files!=null) { Arrays.sort(files); for(File f:files) add(c,f); } return c; }
    public boolean isChildDocument(String parent,String child) { try { return file(child).toPath().startsWith(file(parent).toPath()); } catch(Exception e) { return false; } }
    public String createDocument(String parent,String mime,String name) throws FileNotFoundException { try { if(!name.equals(new File(name).getName())) throw new FileNotFoundException(); File f=new File(file(parent),name); int n=2; while(f.exists()) f=new File(file(parent),name+" ("+(n++)+")"); boolean ok=mime.equals(Document.MIME_TYPE_DIR)?f.mkdir():f.createNewFile(); if(!ok) throw new FileNotFoundException(); return id(f); } catch(java.io.IOException e) { throw new FileNotFoundException(name); } }
    private void remove(File f) { File[] children=f.listFiles(); if(children!=null) for(File c:children) remove(c); f.delete(); }
    public void deleteDocument(String id) throws FileNotFoundException { remove(file(id)); }
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException { android.content.SharedPreferences prefs=getContext().getSharedPreferences("storage-fault",0); if(mode.contains("w")) { int delay=prefs.getInt("writeDelay",0); if(delay>0) {prefs.edit().putBoolean("writing",true).commit(); try {Thread.sleep(delay);} catch(InterruptedException e) {Thread.currentThread().interrupt();} finally {prefs.edit().putBoolean("writing",false).commit();}} int left=prefs.getInt("failAfter",-1); if(left==0) { prefs.edit().putInt("failAfter",-1).commit(); throw new FileNotFoundException("Injected export interruption"); } if(left>0) prefs.edit().putInt("failAfter",left-1).commit(); } return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode)); }
    public Bundle call(String method,String arg,Bundle extras) { if(method.equals("writeDelay")) {getContext().getSharedPreferences("storage-fault",0).edit().putInt("writeDelay",extras.getInt("ms",0)).putBoolean("writing",false).commit(); return new Bundle();} if(method.equals("writeStatus")) {Bundle b=new Bundle();b.putBoolean("writing",getContext().getSharedPreferences("storage-fault",0).getBoolean("writing",false));return b;} if(method.equals("fault")) { getContext().getSharedPreferences("storage-fault",0).edit().putInt("failAfter",extras.getInt("after")).commit(); return new Bundle(); } return super.call(method,arg,extras); }
}
