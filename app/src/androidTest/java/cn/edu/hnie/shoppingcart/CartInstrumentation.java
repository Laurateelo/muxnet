package cn.edu.hnie.shoppingcart;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 真正运行在 Android 设备上的集成测试，不依赖模拟的 UI。仅测试 APK 包含此类。 */
public final class CartInstrumentation extends Instrumentation {
    private MainActivity main;
    private int checks;
    private final StringBuilder log = new StringBuilder();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(info);
            getTargetContext().getSharedPreferences(CartRepository.PREFS_NAME, 0).edit().clear().commit();
            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            main = (MainActivity) startActivitySync(intent);
            settle();
            check(rows().isEmpty(), "首次运行显示空购物车");
            screenshot("01_empty");

            input("牛奶", "12.50", "2"); click(R.id.add);
            input("苹果", "5.80", "3"); click(R.id.add);
            check(rows().size() == 2 && total() == 4240, "添加牛奶和苹果，总额 42.40 元");
            check(text(R.id.tv_summary).contains("42.40"), "列表汇总与金额计算一致");
            screenshot("02_added");

            edit(R.id.et_search, "牛"); click(R.id.query);
            check(listCount() == 1, "按名称关键字查询仅匹配牛奶");
            check(rows().size() == 2, "筛选不删除底层数据");
            screenshot("03_query");
            selectFirst(); input("牛奶", "13.20", "3"); click(R.id.update);
            check(rows().get(0).priceCents == 1320 && rows().get(0).quantity == 3 && total() == 5700,
                    "筛选状态修改正确记录，总额变为 57.00 元");
            click(R.id.show_all); screenshot("04_updated");

            selectFirst();
            ActivityMonitor detailMonitor = addMonitor(DetailActivity.class.getName(), null, false);
            click(R.id.detail);
            Activity detail = waitForMonitorWithTimeout(detailMonitor, 10000);
            check(detail != null, "显式 Intent 打开 DetailActivity");
            final String[] detailText = {""};
            runOnMainSync(() -> detailText[0] = ((TextView) detail.findViewById(R.id.detail_subtotal)).getText().toString());
            check(detailText[0].contains("39.60"), "详情页按商品 ID 读取持久化数据");
            screenshot("05_detail");
            runOnMainSync(detail::finish); settle(); removeMonitor(detailMonitor);

            edit(R.id.et_search, "苹果"); click(R.id.query); selectFirst(); click(R.id.delete);
            screenshot("06_delete_confirm");
            accessibilityClick("android:id/button2");
            check(rows().size() == 2, "取消删除保留全部数据");
            click(R.id.delete); accessibilityClick("android:id/button1");
            check(rows().size() == 1 && rows().get(0).name.equals("牛奶"), "筛选后删除仅移除选中商品");
            click(R.id.show_all); screenshot("07_deleted");

            input("", "1", "1"); click(R.id.add);
            check(rows().size() == 1 && text(R.id.tv_status).contains("名称"), "空名称被拒绝");
            input("错误商品", "1.234", "1"); click(R.id.add);
            check(rows().size() == 1 && text(R.id.tv_status).contains("两位小数"), "价格超过两位小数被拒绝");
            input("错误商品", "1", "0"); click(R.id.add);
            check(rows().size() == 1 && text(R.id.tv_status).contains("9999"), "零数量被拒绝");
            screenshot("08_validation");

            click(R.id.clear); click(R.id.save); click(R.id.load);
            check(rows().size() == 1 && listCount() == 1 && total() == 3960, "保存和重新加载保持商品及总额");
            edit(R.id.et_search, "不存在"); click(R.id.query);
            check(listCount() == 0 && rows().size() == 1, "未命中查询显示空结果，持久化数据不受影响");
            click(R.id.show_all);
            input("牛奶", "1.00", "1"); click(R.id.add);
            check(rows().size() == 2 && !rows().get(0).id.equals(rows().get(1).id), "同名商品使用独立 ID");
            selectAt(1); click(R.id.delete); accessibilityClick("android:id/button1");
            check(rows().size() == 1 && rows().get(0).priceCents == 1320, "同名商品删除不会误删另一条");

            selectFirst(); edit(R.id.et_number, "4");
            ActivityMonitor recreateMonitor = addMonitor(MainActivity.class.getName(), null, false);
            runOnMainSync(() -> main.recreate());
            Activity recreated = waitForMonitorWithTimeout(recreateMonitor, 10000);
            check(recreated != null, "Activity 成功销毁重建");
            main = (MainActivity) recreated; settle(); removeMonitor(recreateMonitor);
            check(text(R.id.et_number).equals("4") && text(R.id.tv_selected).contains("牛奶"), "Activity 重建后保留选中商品和未提交输入");
            click(R.id.update);
            check(total() == 5280, "重建后仍能修改原选中记录");
            selectFirst(); edit(R.id.et_number,"3"); click(R.id.update);

            // 验证损坏的数据不会被静默覆盖；随后恢复测试前的有效内容。
            String good = getTargetContext().getSharedPreferences(CartRepository.PREFS_NAME, 0)
                    .getString(CartRepository.KEY_PRODUCTS, "[]");
            getTargetContext().getSharedPreferences(CartRepository.PREFS_NAME, 0).edit()
                    .putString(CartRepository.KEY_PRODUCTS, "broken-json").commit();
            click(R.id.load); click(R.id.save);
            check(getTargetContext().getSharedPreferences(CartRepository.PREFS_NAME, 0)
                    .getString(CartRepository.KEY_PRODUCTS, "").equals("broken-json"), "损坏 JSON 被提示并保护，未用空数据覆盖");
            getTargetContext().getSharedPreferences(CartRepository.PREFS_NAME, 0).edit()
                    .putString(CartRepository.KEY_PRODUCTS, good).commit();
            click(R.id.load); screenshot("09_reloaded");
            log.append("RESULT: PASS; checks=").append(checks).append('\n');
            result.putString("stream", "\n" + log);
            writeLog(); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            log.append("FAIL: ").append(error).append('\n');
            result.putString("stream", "\n"+log);
            try { writeLog(); } catch (Exception ignored) { }
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void edit(int id, String value) { runOnMainSync(() -> ((EditText) main.findViewById(id)).setText(value)); }
    private void input(String name,String price,String quantity) { edit(R.id.et_name,name); edit(R.id.et_price,price); edit(R.id.et_number,quantity); }
    private void click(int id) { runOnMainSync(() -> main.findViewById(id).performClick()); settle(); }
    private String text(int id) {
        final String[] value = {""};
        runOnMainSync(() -> value[0] = ((TextView) main.findViewById(id)).getText().toString());
        return value[0];
    }
    private int listCount() {
        final int[] count = {0}; runOnMainSync(() -> count[0] = ((ListView) main.findViewById(R.id.listView)).getCount()); return count[0];
    }
    private void selectFirst() { selectAt(0); }
    private void selectAt(int index) {
        runOnMainSync(() -> {
            ListView list = main.findViewById(R.id.listView);
            list.performItemClick(list.getChildAt(index), index, list.getAdapter().getItemId(index));
        }); settle();
    }
    private List<Product> rows() throws JSONException { return new CartRepository(getTargetContext()).load(); }
    private long total() throws JSONException { long sum=0; for(Product p:rows()) sum+=p.subtotalCents(); return sum; }
    private void check(boolean condition, String label) {
        if(!condition) throw new AssertionError(label);
        checks++; log.append("PASS ").append(checks).append("  ").append(label).append('\n');
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(450); }
    private void accessibilityClick(String id) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        if(nodes.isEmpty()) throw new AssertionError("Dialog button missing: "+id);
        nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK); settle();
    }
    private File evidenceDirectory() {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "evidence"); dir.mkdirs(); return dir;
    }
    private void screenshot(String name) throws Exception {
        settle(); Bitmap bitmap = getUiAutomation().takeScreenshot();
        if(bitmap == null) throw new AssertionError("截图失败");
        try(FileOutputStream out=new FileOutputStream(new File(evidenceDirectory(),name+".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        bitmap.recycle();
    }
    private void writeLog() throws Exception {
        try(FileOutputStream out=new FileOutputStream(new File(evidenceDirectory(),"instrumentation-results.txt"))) {
            out.write(log.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
