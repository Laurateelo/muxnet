package cn.edu.hnie.shoppingcart;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProductTest {
    @Test public void decimalMoneyIsExact() {
        Product p = Product.fromInput("p1", "牛奶", "0.10", "3");
        assertEquals(30, p.subtotalCents()); assertEquals("0.30", Product.money(p.subtotalCents()));
    }
    @Test public void normalProductAndUnicodeSearch() {
        Product p = Product.fromInput("p1", "  全脂牛奶  ", "12.50", "2");
        assertEquals("全脂牛奶", p.name); assertEquals(2500, p.subtotalCents());
        assertTrue(p.matches("牛奶")); assertFalse(p.matches("苹果")); assertTrue(p.matches(""));
    }
    @Test public void caseInsensitiveSearch() {
        assertTrue(Product.fromInput("x", "Milk", "3", "2").matches(" MILK "));
    }
    @Test public void maxValuesDoNotOverflowInt() {
        Product p=Product.fromInput("max", "最大值", "999999.99", "9999");
        assertEquals(999899990001L,p.subtotalCents());
        assertEquals("9998999900.01",Product.money(p.subtotalCents()));
    }
    @Test public void invalidNamesRejected() {
        invalid("  ","1","1"); invalid("a".repeat(81),"1","1");
    }
    @Test public void invalidPricesRejected() {
        for(String p:new String[]{"", "0", "-1", "1.234", "abc", "1e3", "NaN", "1000000", ".50"}) invalid("书",p,"1");
    }
    @Test public void invalidQuantitiesRejected() {
        for(String q:new String[]{"", "0", "-1", "1.5", "10000", "abc", "2147483648"}) invalid("书","1",q);
    }
    @Test public void sameNameProductsKeepDistinctIdentity() {
        Product a=Product.fromInput("a","牛奶","5","1");
        Product b=Product.fromInput("b","牛奶","6","2");
        assertNotEquals(a.id,b.id);
    }
    private void invalid(String name,String price,String quantity) {
        try { Product.fromInput("p",name,price,quantity); fail("应拒绝非法输入"); }
        catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
}
