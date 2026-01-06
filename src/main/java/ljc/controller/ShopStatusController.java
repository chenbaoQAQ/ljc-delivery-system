package ljc.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import ljc.entity.DeliveryDetail;
import ljc.entity.DeliveryTemplate;
import ljc.service.ShopDeliveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/status")
@CrossOrigin
public class ShopStatusController {

    @Autowired
    private ShopDeliveryService shopDeliveryService;

    @GetMapping("/templates")
    public List<DeliveryTemplate> getTemplates(@RequestParam String yearMonth) {
        return shopDeliveryService.getTemplatesByMonth(yearMonth);
    }

    @PostMapping("/template")
    public String saveTemplate(@RequestBody DeliveryTemplate t) {
        shopDeliveryService.saveTemplate(t);
        return "SUCCESS";
    }

    @DeleteMapping("/template/{id}")
    public String deleteTemplate(@PathVariable Integer id) {
        shopDeliveryService.deleteTemplate(id);
        return "SUCCESS";
    }

    @GetMapping("/auto-report")
    public Map<String, Object> getAutoReport(@RequestParam String yearMonth, @RequestParam(defaultValue="1") int current) {
        return shopDeliveryService.getAutoAuditReport(yearMonth, current, 10);
    }

    @GetMapping("/details")
    public IPage<DeliveryDetail> getDetails(@RequestParam String yearMonth, @RequestParam(defaultValue="1") int current) {
        return shopDeliveryService.getDetailPage(yearMonth, current, 50);
    }

    @PostMapping("/upload-csv")
    public Map<String, Object> uploadCsv(@RequestParam("file") MultipartFile file, @RequestParam("yearMonth") String yearMonth) {
        Map<String, Object> response = new HashMap<>();
        try {
            shopDeliveryService.importCsv(file.getInputStream(), yearMonth);
            response.put("status", "SUCCESS");
            return response;
        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }
}