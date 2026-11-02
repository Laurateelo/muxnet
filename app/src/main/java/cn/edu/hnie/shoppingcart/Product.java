package cn.edu.hnie.shoppingcart;

import java.math.BigDecimal;
import java.util.Locale;

/** 金额用整数分存储，避免 double 的二进制浮点误差。 */
public final class Product {
    public final String id;
    public final String name;
    public final long priceCents;
    public final int quantity;

    public Product(String id, String name, long priceCents, int quantity) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("商品编号不能为空");
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 80)
            throw new IllegalArgumentException("名称需为 1 至 80 个字符");
        if (priceCents <= 0 || priceCents > 99_999_999L)
            throw new IllegalArgumentException("价格须在 0.01 至 999999.99 元之间");
        if (quantity < 1 || quantity > 9999)
            throw new IllegalArgumentException("数量须为 1 至 9999 的整数");
        this.id = id;
        this.name = clean;
        this.priceCents = priceCents;
        this.quantity = quantity;
    }

    public static Product fromInput(String id, String name, String price, String quantity) {
        String p = price == null ? "" : price.trim();
        String q = quantity == null ? "" : quantity.trim();
        if (!p.matches("[0-9]{1,6}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("价格须为正数，最多保留两位小数");
        if (!q.matches("[0-9]{1,4}"))
            throw new IllegalArgumentException("数量须为 1 至 9999 的整数");
        return new Product(id, name, new BigDecimal(p).movePointRight(2).longValueExact(),
                Integer.parseInt(q));
    }

    public long subtotalCents() { return priceCents * quantity; }

    public boolean matches(String keyword) {
        return name.toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT));
    }

    public static String money(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
