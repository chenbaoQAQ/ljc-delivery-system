package ljc.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;

@Data
@TableName("delivery_detail")
public class DeliveryDetail {
    @TableId // 直接使用 CSV 中的 ID
    private Long id;
    private String shopId;
    private LocalDate date;
    private Integer qty;
}