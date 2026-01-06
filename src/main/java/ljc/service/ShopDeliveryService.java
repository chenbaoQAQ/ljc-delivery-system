package ljc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import ljc.entity.DeliveryDetail;
import ljc.entity.ShopDeliveryStatus;
import ljc.mapper.DeliveryDetailMapper;
import ljc.mapper.ShopDeliveryStatusMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ShopDeliveryService extends ServiceImpl<DeliveryDetailMapper, DeliveryDetail> {

    @Autowired
    private DeliveryDetailMapper detailMapper;

    @Autowired
    private ShopDeliveryStatusMapper statusMapper;

    /**
     * 门店状态表逻辑 (纵向列表样式)
     * 逻辑：shopid | date | status (0A, 1B, C 等)
     */
    public Map<String, Object> getShopStatusReport(String yearMonth, int current, int size) {
        // 1. 获取当月所有的底稿状态 (0, 1, 9, C)
        List<ShopDeliveryStatus> baseStatuses = statusMapper.selectList(
                new LambdaQueryWrapper<ShopDeliveryStatus>()
                        .apply("DATE_FORMAT(date, '%Y-%m') = {0}", yearMonth)
                        .orderByAsc(ShopDeliveryStatus::getShopId, ShopDeliveryStatus::getDate)
        );

        // 2. 获取当月实际配送汇总 (从百万级明细中聚合)
        List<Map<String, Object>> deliverySummary = detailMapper.selectMonthlyDeliveryStatus(yearMonth);
        // 转为 Map 方便查询: "shopId_date" -> hasDelivery(boolean)
        Set<String> deliveryKeySet = deliverySummary.stream()
                .filter(m -> ((Number) m.get("totalQty")).intValue() > 0)
                .map(m -> m.get("shop_id").toString() + "_" + m.get("date").toString())
                .collect(Collectors.toSet());

        // 3. 组装最终状态
        List<Map<String, Object>> fullList = new ArrayList<>();
        for (ShopDeliveryStatus sds : baseStatuses) {
            String base = sds.getShopStatus(); // 获取底稿状态，例如 "0A" 或 "1"
            String key = sds.getShopId() + "_" + sds.getDate().toString();

            String finalStatus;
            if ("C".equalsIgnoreCase(base) || (base != null && base.contains("C"))) {
                finalStatus = "C"; // 状态不正常
            } else {
                // 核心修复逻辑：只取第一个字符作为基础数字 (0, 1, 9)
                // 这样即便数据库存的是 "0A"，我们也只拿 "0"
                String prefix = (base != null && base.length() > 0) ? base.substring(0, 1) : "";

                // 判断今日是否有配送：有则拼 A，无则拼 B
                String suffix = deliveryKeySet.contains(key) ? "A" : "B";
                finalStatus = prefix + suffix;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shopId", sds.getShopId());
            row.put("date", sds.getDate().toString());
            row.put("status", finalStatus);
            fullList.add(row);
        }

        // 4. 手动分页处理 (状态表行数 = 门店数 * 天数)
        int total = fullList.size();
        int start = (current - 1) * size;
        int end = Math.min(start + size, total);
        List<Map<String, Object>> pagedList = (start < total) ? fullList.subList(start, end) : new ArrayList<>();

        Map<String, Object> res = new HashMap<>();
        res.put("records", pagedList);
        res.put("total", total);
        res.put("pages", (int) Math.ceil((double) total / size));
        res.put("current", current);
        return res;
    }

    /**
     * 原始明细分页保持不变
     */
    public IPage<DeliveryDetail> getDetailPage(String yearMonth, int current, int size) {
        return this.page(new Page<>(current, size), new LambdaQueryWrapper<DeliveryDetail>()
                .likeRight(DeliveryDetail::getDate, yearMonth).orderByAsc(DeliveryDetail::getDate));
    }
    /**
     * 导入 CSV (覆盖模式)
     * 增加 yearMonth 参数，确保导入前清理旧数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void importCsv(InputStream is, String yearMonth) throws Exception {
        // 1. 生成本次上传的唯一版本号 (UUID)
        String currentBatchNo = UUID.randomUUID().toString();

        // 2. 幂等性处理：依然先清理该月份数据（如果你想做“全量覆盖”）
        detailMapper.delete(new LambdaQueryWrapper<DeliveryDetail>()
                .likeRight(DeliveryDetail::getDate, yearMonth));

        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"), 1024 * 1024);
        String l;
        r.readLine();

        List<DeliveryDetail> batch = new ArrayList<>();
        while ((l = r.readLine()) != null) {
            String[] d = l.split(",");
            if (d.length < 4) continue;

            DeliveryDetail item = new DeliveryDetail();
            item.setShopId(d[0].trim());
            item.setSkuId(d[1].trim());
            item.setQty(Integer.parseInt(d[2].trim()));
            item.setDate(LocalDate.parse(d[3].trim()));

            // 核心修改：给每一条明细贴上本次上传的“身份证”
            item.setBatchNo(currentBatchNo);

            batch.add(item);
            if (batch.size() >= 1000) {
                this.saveBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            this.saveBatch(batch);
        }
        System.out.println("本次成功导入批次号：" + currentBatchNo);
    }

    /**
     * 3. 异常门店报表：找出在 CSV 中有数据但在底稿状态表中未定义的门店
     */
    public Map<String, Object> getExceptionReport(String yearMonth, int current, int size) {
        // 1. 获取当月底稿中定义的所有门店 ID
        Set<String> definedShopIds = statusMapper.selectList(
                new LambdaQueryWrapper<ShopDeliveryStatus>()
                        .apply("DATE_FORMAT(date, '%Y-%m') = {0}", yearMonth)
        ).stream().map(ShopDeliveryStatus::getShopId).collect(Collectors.toSet());

        // 2. 获取当月 CSV 明细中存在的所有记录
        List<DeliveryDetail> allDetails = this.list(new LambdaQueryWrapper<DeliveryDetail>()
                .likeRight(DeliveryDetail::getDate, yearMonth).orderByAsc(DeliveryDetail::getDate));

        // 3. 筛选异常门店：在明细中出现但底稿中没有的门店
        Map<LocalDate, Map<String, Integer>> matrix = new TreeMap<>();
        Set<String> exceptionShopIds = new TreeSet<>();

        for (DeliveryDetail d : allDetails) {
            if (!definedShopIds.contains(d.getShopId())) {
                exceptionShopIds.add(d.getShopId());
                matrix.computeIfAbsent(d.getDate(), k -> new HashMap<>())
                        .merge(d.getShopId(), d.getQty(), Integer::sum);
            }
        }

        // 4. 分页处理门店列
        List<String> fullShopList = new ArrayList<>(exceptionShopIds);
        int start = (current - 1) * size;
        int end = Math.min(start + size, fullShopList.size());
        List<String> pagedShops = (start < fullShopList.size()) ? fullShopList.subList(start, end) : new ArrayList<>();

        // 5. 组装行数据 (日期 | shop1 | shop2 ...)
        List<Map<String, Object>> tableData = new ArrayList<>();
        matrix.forEach((date, shops) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Date", date.toString());
            for (String sid : pagedShops) {
                row.put(sid, shops.getOrDefault(sid, 0));
            }
            tableData.add(row);
        });

        Map<String, Object> res = new HashMap<>();
        res.put("data", tableData);
        res.put("shopList", pagedShops);
        res.put("total", fullShopList.size());
        res.put("pages", (int) Math.ceil((double) fullShopList.size() / size));
        res.put("current", current);
        return res;
    }
}