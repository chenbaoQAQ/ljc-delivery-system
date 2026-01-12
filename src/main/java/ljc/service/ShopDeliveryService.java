package ljc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import ljc.entity.DeliveryDetail;
import ljc.entity.DeliveryTemplate;
import ljc.mapper.DeliveryDetailMapper;
import ljc.mapper.DeliveryTemplateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
public class ShopDeliveryService extends ServiceImpl<DeliveryDetailMapper, DeliveryDetail> {

    @Autowired
    private DeliveryTemplateMapper templateMapper;

    public List<DeliveryTemplate> getTemplatesByMonth(String ym) {
        return templateMapper.selectList(new LambdaQueryWrapper<DeliveryTemplate>().eq(DeliveryTemplate::getYearMonth, ym));
    }

    public void saveTemplate(DeliveryTemplate t) {
        if (t.getId() == null) templateMapper.insert(t);
        else templateMapper.updateById(t);
    }

    public void deleteTemplate(Integer id) { templateMapper.deleteById(id); }

    public Map<String, Object> getAutoAuditReport(String ym, int current, int size) {
        List<DeliveryTemplate> templates = getTemplatesByMonth(ym);
        Page<Map<String, Object>> idPage = baseMapper.selectMapsPage(new Page<>(current, size),
                new QueryWrapper<DeliveryDetail>().select("distinct shop_id").likeRight("date", ym).orderByAsc("shop_id"));
        List<String> shopIds = idPage.getRecords().stream().map(m -> m.get("shop_id").toString()).toList();
        if (shopIds.isEmpty()) return Collections.emptyMap();

        Map<String, Object> res = buildAuditMatrix(ym, shopIds, templates);
        List<Map<String, Object>> allStatus = baseMapper.selectMonthlyDeliveryStatus(ym);
        Map<String, Map<Integer, Integer>> allShopData = groupShopData(allStatus);
        int days = (int) res.get("days");

        long abnormalCount = allShopData.entrySet().stream()
                .filter(e -> (boolean) processAuditRow(e.getKey(), e.getValue(), templates, days).get("hasError"))
                .count();

        res.put("total", idPage.getTotal());
        res.put("abnormalCount", abnormalCount);
        res.put("qualifiedCount", idPage.getTotal() - abnormalCount);
        res.put("pages", idPage.getPages());
        res.put("current", current);
        return res;
    }

    public Map<String, Object> getGlobalExceptionMatrix(String ym) {
        List<DeliveryTemplate> templates = getTemplatesByMonth(ym);
        List<Map<String, Object>> allStatus = baseMapper.selectMonthlyDeliveryStatus(ym);
        Map<String, Map<Integer, Integer>> shopData = groupShopData(allStatus);
        int days = YearMonth.parse(ym).lengthOfMonth();

        List<Map<String, Object>> exceptions = shopData.entrySet().stream()
                .map(e -> processAuditRow(e.getKey(), e.getValue(), templates, days))
                .filter(row -> (boolean) row.get("hasError"))
                .toList();

        return Map.of("records", exceptions, "days", days);
    }

    // --- 导出：正常门店 (列表格式) ---
    public String exportQualifiedShopsCsv(String ym) {
        List<DeliveryTemplate> templates = getTemplatesByMonth(ym);
        List<Map<String, Object>> allStatus = baseMapper.selectMonthlyDeliveryStatus(ym);
        Map<String, Map<Integer, Integer>> shopData = groupShopData(allStatus);
        int days = YearMonth.parse(ym).lengthOfMonth();

        StringBuilder csv = new StringBuilder("\uFEFF门店ID,日期,审计状态\n");
        shopData.forEach((sid, actuals) -> {
            Map<String, Object> row = processAuditRow(sid, actuals, templates, days);
            if (!(boolean) row.get("hasError")) {
                String sign = row.get("bestTemplate").toString().substring(0, 1);
                for (int d = 1; d <= days; d++) {
                    String dateStr = ym + "-" + String.format("%02d", d);
                    // 正常门店每一天都是符合底板的
                    int act = actuals.getOrDefault(d, 0) > 0 ? 1 : 0;
                    csv.append(sid).append(",").append(dateStr).append(",").append(act).append(sign).append("\n");
                }
            }
        });
        return csv.toString();
    }

    // --- 导出：异常门店 (大表矩阵格式) ---
    public String exportExceptionMatrixCsv(String ym) {
        List<DeliveryTemplate> templates = getTemplatesByMonth(ym);
        List<Map<String, Object>> allStatus = baseMapper.selectMonthlyDeliveryStatus(ym);
        Map<String, Map<Integer, Integer>> shopData = groupShopData(allStatus);
        int days = YearMonth.parse(ym).lengthOfMonth();

        StringBuilder csv = new StringBuilder("\uFEFF门店ID,匹配底板");
        for (int i = 1; i <= days; i++) csv.append(",").append(i);
        csv.append("\n");

        shopData.forEach((sid, actuals) -> {
            Map<String, Object> row = processAuditRow(sid, actuals, templates, days);
            if ((boolean) row.get("hasError")) {
                csv.append(sid).append(",").append(row.get("bestTemplate"));
                for (int d = 1; d <= days; d++) csv.append(",").append(row.get("day" + d));
                csv.append("\n");
            }
        });
        return csv.toString();
    }

    private Map<String, Object> buildAuditMatrix(String ym, List<String> shopIds, List<DeliveryTemplate> templates) {
        List<Map<String, Object>> details = baseMapper.selectMonthlySummaryByShops(ym, shopIds);
        Map<String, Map<Integer, Integer>> shopData = groupShopData(details);
        int days = YearMonth.parse(ym).lengthOfMonth();
        List<Map<String, Object>> records = shopIds.stream()
                .map(id -> processAuditRow(id, shopData.getOrDefault(id, Map.of()), templates, days))
                .toList();
        return new HashMap<>(Map.of("records", records, "days", days));
    }

    private Map<String, Map<Integer, Integer>> groupShopData(List<Map<String, Object>> data) {
        Map<String, Map<Integer, Integer>> grouped = new HashMap<>();
        for (Map<String, Object> m : data) {
            int day = LocalDate.parse(m.get("date").toString()).getDayOfMonth();
            grouped.computeIfAbsent(m.get("shopId").toString(), k -> new HashMap<>()).put(day, ((Number) m.get("totalQty")).intValue());
        }
        return grouped;
    }

    private Map<String, Object> processAuditRow(String sid, Map<Integer, Integer> actuals, List<DeliveryTemplate> ts, int days) {
        DeliveryTemplate best = findBest(actuals, ts, days);
        String[] cfg = best != null ? best.getConfig().split(",") : new String[0];
        Map<String, Object> row = new LinkedHashMap<>(Map.of("shopId", sid, "bestTemplate", best != null ? best.getTemplateName() : "无匹配"));
        boolean hasErr = false;
        for (int d = 1; d <= days; d++) {
            int act = actuals.getOrDefault(d, 0) > 0 ? 1 : 0;
            int shd = (best != null && d <= cfg.length) ? Integer.parseInt(cfg[d-1].trim()) : 0;
            boolean err = act != shd;
            if (err) hasErr = true;
            row.put("day" + d, act + (best != null ? best.getTemplateName().substring(0,1) : "?"));
            row.put("isError" + d, err);
        }
        row.put("hasError", hasErr);
        return row;
    }

    private DeliveryTemplate findBest(Map<Integer, Integer> act, List<DeliveryTemplate> ts, int days) {
        return ts.stream().min(Comparator.comparingInt(t -> {
            String[] cfg = t.getConfig().split(",");
            int diff = 0;
            for (int i = 0; i < days; i++) {
                int s = (i < cfg.length) ? Integer.parseInt(cfg[i].trim()) : 0;
                if (s != (act.getOrDefault(i+1, 0) > 0 ? 1 : 0)) diff++;
            }
            return diff;
        })).orElse(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void importCsv(InputStream is, String ym) throws Exception {
        this.remove(new LambdaQueryWrapper<DeliveryDetail>().likeRight(DeliveryDetail::getDate, ym));
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            br.readLine();
            List<DeliveryDetail> batch = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                String[] c = line.replace("\"", "").split(",");
                if (c.length < 4 || !c[1].trim().startsWith(ym)) continue;
                DeliveryDetail d = new DeliveryDetail();
                d.setId(Long.parseLong(c[0].trim())); d.setDate(LocalDate.parse(c[1].trim()));
                d.setShopId(c[2].trim()); d.setQty(Integer.parseInt(c[3].trim()));
                batch.add(d);
                if (batch.size() >= 1000) { this.saveBatch(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) this.saveBatch(batch);
        }
    }
}