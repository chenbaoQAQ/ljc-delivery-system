package ljc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.*;

@Service
public class ShopDeliveryService extends ServiceImpl<DeliveryDetailMapper, DeliveryDetail> {

    @Autowired
    private DeliveryTemplateMapper templateMapper;

    public IPage<DeliveryDetail> getDetailPage(String yearMonth, int current, int size) {
        return this.page(new Page<>(current, size), new LambdaQueryWrapper<DeliveryDetail>()
                .likeRight(DeliveryDetail::getDate, yearMonth).orderByAsc(DeliveryDetail::getDate));
    }

    public List<DeliveryTemplate> getTemplatesByMonth(String ym) {
        return templateMapper.selectList(new LambdaQueryWrapper<DeliveryTemplate>().eq(DeliveryTemplate::getYearMonth, ym));
    }

    public void saveTemplate(DeliveryTemplate t) {
        if (t.getId() == null) templateMapper.insert(t);
        else templateMapper.updateById(t);
    }

    public void deleteTemplate(Integer id) { templateMapper.deleteById(id); }

    public Map<String, Object> getAutoAuditReport(String yearMonth, int current, int size) {
        List<DeliveryTemplate> templates = getTemplatesByMonth(yearMonth);
        List<Map<String, Object>> allDetails = baseMapper.selectMonthlyDeliveryStatus(yearMonth);

        // 门店数据索引 Map<shopId, Map<day, qty>>
        Map<String, Map<Integer, Integer>> shopData = new HashMap<>();
        for (Map<String, Object> m : allDetails) {
            String sid = m.get("shopId").toString();
            int day = LocalDate.parse(m.get("date").toString()).getDayOfMonth();
            shopData.computeIfAbsent(sid, k -> new HashMap<>()).put(day, ((Number) m.get("totalQty")).intValue());
        }

        List<String> allShopIds = new ArrayList<>(shopData.keySet());
        Collections.sort(allShopIds);

        int total = allShopIds.size();
        int pages = (int) Math.ceil((double) total / size);
        int start = (current - 1) * size;
        int end = Math.min(start + size, total);
        List<String> pagedShopIds = (start < total) ? allShopIds.subList(start, end) : new ArrayList<>();

        List<Map<String, Object>> records = new ArrayList<>();
        int daysInMonth = LocalDate.parse(yearMonth + "-01").lengthOfMonth();

        for (String sid : pagedShopIds) {
            Map<Integer, Integer> dailyActual = shopData.get(sid);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shopId", sid);

            // 寻找最匹配模板
            DeliveryTemplate bestT = null;
            int minDiff = Integer.MAX_VALUE;
            for (DeliveryTemplate t : templates) {
                String[] cfg = t.getConfig().split(",");
                int diff = 0;
                for (int i = 0; i < cfg.length; i++) {
                    int day = i + 1;
                    int should = Integer.parseInt(cfg[i].trim());
                    int actual = dailyActual.getOrDefault(day, 0) > 0 ? 1 : 0;
                    if (should != actual) diff++;
                }
                if (diff < minDiff) { minDiff = diff; bestT = t; }
            }

            // 提取模板名称首字母作为标识
            String signLetter = (bestT != null && !bestT.getTemplateName().isEmpty())
                    ? bestT.getTemplateName().substring(0, 1) : "?";
            row.put("bestTemplate", bestT != null ? bestT.getTemplateName() : "无匹配");

            String[] bestCfg = (bestT != null) ? bestT.getConfig().split(",") : new String[daysInMonth];
            for (int d = 1; d <= daysInMonth; d++) {
                int actualQty = dailyActual.getOrDefault(d, 0);
                int actualStatus = actualQty > 0 ? 1 : 0; // 大于0统一按1处理
                int shouldStatus = (bestT != null && d <= bestCfg.length) ? Integer.parseInt(bestCfg[d-1].trim()) : 0;

                // 组装掩码：0A / 1A 这种格式
                row.put("day" + d, actualStatus + signLetter);

                // 只有状态不一致时才标红
                row.put("isError" + d, actualStatus != shouldStatus);
            }
            records.add(row);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("records", records);
        res.put("days", daysInMonth);
        res.put("total", total);
        res.put("pages", pages);
        res.put("current", current);
        return res;
    }

    @Transactional(rollbackFor = Exception.class)
    public void importCsv(InputStream is, String yearMonth) throws Exception {
        this.remove(new LambdaQueryWrapper<DeliveryDetail>().likeRight(DeliveryDetail::getDate, yearMonth));
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            br.readLine();
            List<DeliveryDetail> batch = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",");
                if (cols.length < 4) continue;
                DeliveryDetail d = new DeliveryDetail();
                d.setShopId(cols[0].trim()); d.setSkuId(cols[1].trim());
                d.setQty(Integer.parseInt(cols[2].trim()));
                d.setDate(LocalDate.parse(cols[3].trim()));
                batch.add(d);
                if (batch.size() >= 1000) { this.saveBatch(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) this.saveBatch(batch);
        }
    }
}