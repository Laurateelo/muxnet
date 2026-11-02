package cn.edu.hnie.shoppingcart;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.util.List;

public final class CartAdapter extends BaseAdapter {
    private final List<Product> rows;
    private final LayoutInflater inflater;
    private String selectedId;

    public CartAdapter(Context context, List<Product> rows) {
        this.rows = rows;
        inflater = LayoutInflater.from(context);
    }
    public void setSelectedId(String id) { selectedId = id; notifyDataSetChanged(); }
    @Override public int getCount() { return rows.size(); }
    @Override public Product getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView == null ? inflater.inflate(R.layout.listview_item, parent, false) : convertView;
        Product p = getItem(position);
        ((TextView) row.findViewById(R.id.tv_name)).setText(p.name);
        ((TextView) row.findViewById(R.id.tv_price)).setText("单价  ¥ " + Product.money(p.priceCents));
        ((TextView) row.findViewById(R.id.tv_number)).setText("数量  " + p.quantity);
        ((TextView) row.findViewById(R.id.tv_subtotal)).setText("¥ " + Product.money(p.subtotalCents()));
        row.setBackgroundResource(p.id.equals(selectedId) ? R.drawable.row_selected : R.drawable.row_normal);
        return row;
    }
}
