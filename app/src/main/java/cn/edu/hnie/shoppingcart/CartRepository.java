package cn.edu.hnie.shoppingcart;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 使用应用私有 SharedPreferences 保存 JSON 商品数组。 */
public final class CartRepository {
    public static final String PREFS_NAME = "shopping_cart";
    public static final String KEY_PRODUCTS = "products_json";
    private final SharedPreferences prefs;

    public CartRepository(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<Product> load() throws JSONException {
        JSONArray array = new JSONArray(prefs.getString(KEY_PRODUCTS, "[]"));
        List<Product> products = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                Product p = new Product(row.getString("id"), row.getString("name"),
                        row.getLong("priceCents"), row.getInt("quantity"));
                if (!ids.add(p.id)) throw new JSONException("商品编号重复");
                products.add(p);
            }
        } catch (IllegalArgumentException ex) {
            throw new JSONException(ex.getMessage());
        }
        return products;
    }

    public void save(List<Product> products) throws JSONException {
        JSONArray array = new JSONArray();
        for (Product p : products) {
            JSONObject row = new JSONObject();
            row.put("id", p.id);
            row.put("name", p.name);
            row.put("priceCents", p.priceCents);
            row.put("quantity", p.quantity);
            array.put(row);
        }
        // apply 立即更新内存，异步写盘；不把保存推迟到不保证调用的 onDestroy。
        prefs.edit().putString(KEY_PRODUCTS, array.toString()).apply();
    }

    public Product findById(String id) throws JSONException {
        for (Product product : load()) if (product.id.equals(id)) return product;
        return null;
    }
}
