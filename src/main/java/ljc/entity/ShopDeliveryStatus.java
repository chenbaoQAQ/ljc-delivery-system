package ljc.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@TableName("shop_delivery_status")
public class ShopDeliveryStatus {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String shopId;
    private LocalDate date;
    private String shopStatus; // 对应 0A, 1B 等
}