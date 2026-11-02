package cn.edu.hnie.shoppingcart;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import org.json.JSONException;

public final class DetailActivity extends Activity {
    public static final String EXTRA_PRODUCT_ID = "product_id";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.d("CartLifecycle", "Detail onCreate");
        setContentView(R.layout.activity_detail);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        String id = getIntent().getStringExtra(EXTRA_PRODUCT_ID);
        try {
            Product p = new CartRepository(this).findById(id);
            if (p == null) {
                ((TextView) findViewById(R.id.detail_name)).setText("商品不存在或已删除"); return;
            }
            ((TextView) findViewById(R.id.detail_name)).setText(p.name);
            ((TextView) findViewById(R.id.detail_price)).setText("单价    ¥ " + Product.money(p.priceCents));
            ((TextView) findViewById(R.id.detail_quantity)).setText("数量    " + p.quantity + " 件");
            ((TextView) findViewById(R.id.detail_subtotal)).setText("小计    ¥ " + Product.money(p.subtotalCents()));
        } catch (JSONException ex) {
            ((TextView) findViewById(R.id.detail_name)).setText("本地数据读取失败");
        }
    }
    @Override protected void onStart() { super.onStart(); Log.d("CartLifecycle", "Detail onStart"); }
    @Override protected void onResume() { super.onResume(); Log.d("CartLifecycle", "Detail onResume"); }
    @Override protected void onPause() { Log.d("CartLifecycle", "Detail onPause"); super.onPause(); }
    @Override protected void onStop() { Log.d("CartLifecycle", "Detail onStop"); super.onStop(); }
    @Override protected void onDestroy() { Log.d("CartLifecycle", "Detail onDestroy"); super.onDestroy(); }
}
