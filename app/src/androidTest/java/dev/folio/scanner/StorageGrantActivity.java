package dev.folio.scanner;
public class StorageGrantActivity extends android.app.Activity {
    protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.net.Uri tree=getIntent().getData()!=null?getIntent().getData():android.provider.DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root");
        if(!"dev.folio.scanner.test.storage".equals(tree.getAuthority())) throw new IllegalArgumentException("Test provider only");
        grantUriPermission("dev.folio.scanner",tree,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION|android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION|android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        finish();
    }
}
