package ljc.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import ljc.entity.DeliveryDetail;
import ljc.service.ShopDeliveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/status")
@CrossOrigin // 解决跨域问题
public class ShopStatusController {

    @Autowired
    private ShopDeliveryService shopDeliveryService;

    /**
     * 1. 获取门店状态表
     * 对应前端：门店状态表 按钮
     */
    @GetMapping("/status-report")
    public Map<String, Object> getStatusReport(
            @RequestParam String yearMonth,
            @RequestParam(defaultValue="1") int current) {
        return shopDeliveryService.getShopStatusReport(yearMonth, current, 50);
    }

    /**
     * 2. 原始明细表
     * 对应前端：原始明细表 按钮
     */
    @GetMapping("/details")
    public IPage<DeliveryDetail> getDetails(
            @RequestParam String yearMonth,
            @RequestParam(defaultValue="1") int current) {
        return shopDeliveryService.getDetailPage(yearMonth, current, 50);
    }

    /**
     * 3. 导入 CSV (核心修正点)
     * 增加了对参数缺失的容错处理
     */
    @PostMapping("/upload-csv")
    public Map<String, Object> uploadCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "yearMonth", required = false) String yearMonth) {

        Map<String, Object> response = new HashMap<>();
        try {
            // 如果前端没传月份，后端可以从文件名或者当前时间补全，这里先抛出具体错误方便你调试
            if (yearMonth == null || yearMonth.isEmpty()) {
                throw new Exception("月份参数(yearMonth)缺失，请检查前端请求");
            }

            // 执行带有 UUID 逻辑的导入
            shopDeliveryService.importCsv(file.getInputStream(), yearMonth);

            response.put("status", "SUCCESS");
            response.put("message", "导入成功");
            return response;
        } catch (Exception e) {
            e.printStackTrace(); // 在 IDEA 控制台打印具体错误堆栈
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    /**
     * 4. 异常门店报表
     * 对应前端：异常门店清单 按钮
     */
    @GetMapping("/exception-report")
    public Map<String, Object> getExceptionReport(
            @RequestParam String yearMonth,
            @RequestParam(defaultValue="1") int current) {
        return shopDeliveryService.getExceptionReport(yearMonth, current, 15);
    }
}