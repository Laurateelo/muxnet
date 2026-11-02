package cn.edu.hnie.shoppingcart;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONException;

public final class MainActivity extends Activity {
    private final List<Product> products = new ArrayList<>();
    private final List<Product> visible = new ArrayList<>();
    private CartRepository repository;
    private CartAdapter adapter;
    private EditText nameInput, priceInput, numberInput, searchInput;
    private TextView selectedText, summary, status;
    private String selectedId;
    private String activeQuery = "";
    private boolean storageHealthy = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.d("CartLifecycle", "Main onCreate");
        setContentView(R.layout.activity_main);
        repository = new CartRepository(this);
        nameInput = findViewById(R.id.et_name);
        priceInput = findViewById(R.id.et_price);
        numberInput = findViewById(R.id.et_number);
        searchInput = findViewById(R.id.et_search);
        selectedText = findViewById(R.id.tv_selected);
        summary = findViewById(R.id.tv_summary);
        status = findViewById(R.id.tv_status);
        ListView list = findViewById(R.id.listView);
        adapter = new CartAdapter(this, visible);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.tv_empty));
        list.setOnItemClickListener((parent, view, position, id) -> select(visible.get(position)));
        findViewById(R.id.add).setOnClickListener(v -> addProduct());
        findViewById(R.id.update).setOnClickListener(v -> updateProduct());
        findViewById(R.id.delete).setOnClickListener(v -> deleteProduct());
        findViewById(R.id.clear).setOnClickListener(v -> { clearEditor(); hideKeyboard(); refresh(); });
        findViewById(R.id.query).setOnClickListener(v -> {
            activeQuery = searchInput.getText().toString().trim();
            clearEditor(); hideKeyboard(); refresh();
            status.setText("查询完成，匹配 " + visible.size() + " 种商品");
        });
        findViewById(R.id.show_all).setOnClickListener(v -> {
            searchInput.setText(""); activeQuery = "";
            clearEditor(); hideKeyboard(); refresh(); status.setText("已显示全部商品");
        });
        findViewById(R.id.save).setOnClickListener(v -> {
            if (persist(products)) status.setText("保存已提交，共 " + products.size() + " 种商品");
        });
        findViewById(R.id.load).setOnClickListener(v -> {
            clearEditor(); hideKeyboard(); loadProducts();
        });
        findViewById(R.id.detail).setOnClickListener(v -> {
            if (selected() == null) { message("请先点击列表选择商品"); return; }
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_PRODUCT_ID, selectedId);
            startActivity(intent);
        });
        loadProducts();
        if (state != null) {
            selectedId = state.getString("selected_id");
            activeQuery = state.getString("active_query", "");
            nameInput.setText(state.getString("draft_name", ""));
            priceInput.setText(state.getString("draft_price", ""));
            numberInput.setText(state.getString("draft_quantity", ""));
            searchInput.setText(state.getString("search_text", ""));
            refresh();
        }
    }

    private void loadProducts() {
        try {
            List<Product> loaded = repository.load();
            products.clear(); products.addAll(loaded);
            storageHealthy = true;
            status.setText("已从本地读取 " + products.size() + " 种商品");
        } catch (JSONException ex) {
            // 保留原始 JSON，禁止用空列表覆盖损坏数据。
            storageHealthy = false;
            status.setText("本地数据格式异常，已保留原数据并暂停保存");
            message("读取失败，请检查数据后重新加载");
        }
        refresh();
    }

    private boolean persist(List<Product> next) {
        if (!storageHealthy) { message("本地数据异常，请修复后重新加载"); return false; }
        try { repository.save(next); return true; }
        catch (JSONException ex) { message("保存失败，未更改商品"); return false; }
    }

    private Product readInput(String id) {
        return Product.fromInput(id, nameInput.getText().toString(),
                priceInput.getText().toString(), numberInput.getText().toString());
    }

    private void addProduct() {
        try {
            Product p = readInput(UUID.randomUUID().toString());
            List<Product> next = new ArrayList<>(products); next.add(p);
            if (!persist(next)) return;
            products.clear(); products.addAll(next);
            activeQuery = ""; searchInput.setText(""); clearEditor(); hideKeyboard(); refresh();
            status.setText("已添加并自动保存：" + p.name);
        } catch (IllegalArgumentException ex) { message(ex.getMessage()); }
    }

    private void updateProduct() {
        Product old = selected();
        if (old == null) { message("请先点击列表选择要修改的商品"); return; }
        try {
            Product changed = readInput(old.id);
            List<Product> next = new ArrayList<>(products);
            next.set(products.indexOf(old), changed);
            if (!persist(next)) return;
            products.clear(); products.addAll(next); clearEditor(); hideKeyboard(); refresh();
            status.setText("已修改并自动保存：" + changed.name);
        } catch (IllegalArgumentException ex) { message(ex.getMessage()); }
    }

    private void deleteProduct() {
        Product old = selected();
        if (old == null) { message("请先点击列表选择要删除的商品"); return; }
        new AlertDialog.Builder(this).setTitle("删除商品")
            .setMessage("确定删除“" + old.name + "”？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (dialog, which) -> {
                List<Product> next = new ArrayList<>(products); next.remove(old);
                if (!persist(next)) return;
                products.clear(); products.addAll(next); clearEditor(); hideKeyboard(); refresh();
                status.setText("已删除并自动保存：" + old.name);
            }).show();
    }

    private Product selected() {
        for (Product p : products) if (p.id.equals(selectedId)) return p;
        return null;
    }

    private void select(Product p) {
        selectedId = p.id;
        nameInput.setText(p.name); priceInput.setText(Product.money(p.priceCents));
        numberInput.setText(String.valueOf(p.quantity));
        hideKeyboard(); refresh();
    }

    private void clearEditor() {
        selectedId = null;
        nameInput.setText(""); priceInput.setText(""); numberInput.setText("");
    }

    private void refresh() {
        visible.clear();
        long total = 0, count = 0, visibleTotal = 0;
        for (Product p : products) {
            total += p.subtotalCents(); count += p.quantity;
            if (p.matches(activeQuery)) { visible.add(p); visibleTotal += p.subtotalCents(); }
        }
        Product p = selected();
        if (p == null) selectedId = null;
        selectedText.setText(p == null ? "点击商品可选择，再修改、删除或查看详情" : "已选中：" + p.name);
        adapter.setSelectedId(selectedId);
        summary.setText("共 " + products.size() + " 种 / " + count + " 件   合计 ¥ " + Product.money(total)
                + (activeQuery.isEmpty() ? "" : "\n查询结果 " + visible.size() + " 种   小计 ¥ " + Product.money(visibleTotal)));
        ((TextView) findViewById(R.id.tv_empty)).setText(activeQuery.isEmpty() ? "购物车为空\n添加第一件商品吧" : "没有找到匹配的商品");
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(nameInput.getWindowToken(), 0);
        nameInput.clearFocus(); priceInput.clearFocus(); numberInput.clearFocus(); searchInput.clearFocus();
    }
    private void message(String text) {
        status.setText(text); Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("selected_id", selectedId); out.putString("active_query", activeQuery);
        out.putString("draft_name", nameInput.getText().toString());
        out.putString("draft_price", priceInput.getText().toString());
        out.putString("draft_quantity", numberInput.getText().toString());
        out.putString("search_text", searchInput.getText().toString());
        super.onSaveInstanceState(out);
    }
    @Override protected void onStart() { super.onStart(); Log.d("CartLifecycle", "Main onStart"); }
    @Override protected void onResume() { super.onResume(); Log.d("CartLifecycle", "Main onResume"); }
    @Override protected void onPause() { Log.d("CartLifecycle", "Main onPause"); super.onPause(); }
    @Override protected void onStop() { Log.d("CartLifecycle", "Main onStop"); super.onStop(); }
    @Override protected void onDestroy() { Log.d("CartLifecycle", "Main onDestroy"); super.onDestroy(); }
}
