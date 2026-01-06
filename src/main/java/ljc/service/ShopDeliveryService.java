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
    private ShopDeliveryStatusMapper statusMapper;

    /**
     * 1. 门店状态表 (高性能版)
     */
    public Map<String, Object> getShopStatusReport(String yearMonth, int current, int size) {
        // 1. 分页查底稿：这一步是为了确定“这一页我们要显示哪些店、哪些天”
        Page<ShopDeliveryStatus> page = new Page<>(current, size);
        IPage<ShopDeliveryStatus> statusPage = statusMapper.selectPage(page,
                new LambdaQueryWrapper<ShopDeliveryStatus>()
                        .apply("DATE_FORMAT(date, '%Y-%m') = {0}", yearMonth)
                        .orderByAsc(ShopDeliveryStatus::getShopId, ShopDeliveryStatus::getDate));

        if (statusPage.getRecords().isEmpty()) {
            Map<String, Object> emptyRes = new HashMap<>();
            emptyRes.put("records", new ArrayList<>());
            emptyRes.put("total", 0);
            return emptyRes;
        }

        // 2. 收集当前页出现的门店 ID
        List<String> shopIds = statusPage.getRecords().stream()
                .map(ShopDeliveryStatus::getShopId)
                .distinct()
                .collect(Collectors.toList());

        // 3. 核心优化：去明细表里只查这几个店的汇总（局部聚合）
        // 注意：这里的 baseMapper 实际上就是 detailMapper
        List<Map<String, Object>> deliverySummary = baseMapper.selectMonthlySummaryByShops(yearMonth, shopIds);

        // 4. 将汇总结果转成 Set 方便快速对比： "门店ID_日期" 这种格式
        Set<String> hasDeliverySet = deliverySummary.stream()
                .filter(m -> m.get("totalQty") != null && Double.parseDouble(m.get("totalQty").toString()) > 0)
                .map(m -> m.get("shopId").toString() + "_" + m.get("date").toString())
                .collect(Collectors.toSet());

        // 5. 遍历底稿，根据是否有明细来拼 A 或 B
        List<Map<String, Object>> records = new ArrayList<>();
        for (ShopDeliveryStatus sds : statusPage.getRecords()) {
            String base = sds.getShopStatus(); // 比如数据库里存的是 "0" 或 "1"
            String key = sds.getShopId() + "_" + sds.getDate().toString();

            String finalStatus;
            if (base != null && base.toUpperCase().contains("C")) {
                finalStatus = "C"; // C 状态特殊处理
            } else {
                // 取底稿第一个数字，有配送拼 A，没配送拼 B
                String prefix = (base != null && !base.isEmpty()) ? base.substring(0, 1) : "0";
                finalStatus = prefix + (hasDeliverySet.contains(key) ? "A" : "B");
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shopId", sds.getShopId());
            row.put("date", sds.getDate().toString());
            row.put("status", finalStatus);
            records.add(row);
        }

        // 6. 返回给前端
        Map<String, Object> res = new HashMap<>();
        res.put("records", records);
        res.put("total", statusPage.getTotal());
        res.put("pages", statusPage.getPages());
        res.put("current", current);
        return res;
    }

    /**
     * 2. 原始明细分页 (保持高效)
     */
    public IPage<DeliveryDetail> getDetailPage(String yearMonth, int current, int size) {
        return this.page(new Page<>(current, size), new LambdaQueryWrapper<DeliveryDetail>()
                .likeRight(DeliveryDetail::getDate, yearMonth).orderByAsc(DeliveryDetail::getDate));
    }

    /**
     * 3. 异常门店报表 (逻辑修正)
     * 逻辑：找出当月在 CSV 里有货，但在底稿里没定义的店
     */
    public Map<String, Object> getExceptionReport(String yearMonth, int current, int size) {
        // 获取当月所有定义的店
        Set<String> definedShops = statusMapper.selectList(new LambdaQueryWrapper<ShopDeliveryStatus>()
                        .apply("DATE_FORMAT(date, '%Y-%m') = {0}", yearMonth))
                .stream().map(ShopDeliveryStatus::getShopId).collect(Collectors.toSet());

        // 获取明细汇总
        List<Map<String, Object>> allMonthDetails = baseMapper.selectMonthlyDeliveryStatus(yearMonth);

        // 过滤出异常店
        TreeMap<String, Map<String, Integer>> matrix = new TreeMap<>();
        TreeSet<String> exceptionShopIds = new TreeSet<>();

        for (Map<String, Object> m : allMonthDetails) {
            String sid = m.get("shopId").toString();
            if (!definedShops.contains(sid)) {
                exceptionShopIds.add(sid);
                String date = m.get("date").toString();
                int qty = ((Number) m.get("totalQty")).intValue();
                matrix.computeIfAbsent(date, k -> new HashMap<>()).put(sid, qty);
            }
        }

        // 分页处理
        List<String> sortedShops = new ArrayList<>(exceptionShopIds);
        int start = (current - 1) * size;
        int end = Math.min(start + size, sortedShops.size());
        List<String> pagedShops = (start < sortedShops.size()) ? sortedShops.subList(start, end) : new ArrayList<>();

        List<Map<String, Object>> data = new ArrayList<>();
        matrix.forEach((date, shops) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Date", date);
            pagedShops.forEach(sid -> row.put(sid, shops.getOrDefault(sid, 0)));
            data.add(row);
        });

        Map<String, Object> res = new HashMap<>();
        res.put("data", data);
        res.put("shopList", pagedShops);
        res.put("total", sortedShops.size());
        res.put("pages", (int) Math.ceil((double) sortedShops.size() / size));
        res.put("current", current);
        return res;
    }

    /**
     * 4. 导入 CSV (高性能批处理)
     */
    @Transactional(rollbackFor = Exception.class)
    public void importCsv(InputStream is, String yearMonth) throws Exception {
        String batchNo = UUID.randomUUID().toString();
        // 幂等删除
        this.remove(new LambdaQueryWrapper<DeliveryDetail>().likeRight(DeliveryDetail::getDate, yearMonth));

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            br.readLine(); // skip header
            List<DeliveryDetail> batch = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",");
                if (cols.length < 4) continue;
                DeliveryDetail d = new DeliveryDetail();
                d.setShopId(cols[0].trim());
                d.setSkuId(cols[1].trim());
                d.setQty(Integer.parseInt(cols[2].trim()));
                d.setDate(LocalDate.parse(cols[3].trim()));
                d.setBatchNo(batchNo);
                batch.add(d);
                if (batch.size() >= 1000) {
                    this.saveBatch(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) this.saveBatch(batch);
        }
    }
}