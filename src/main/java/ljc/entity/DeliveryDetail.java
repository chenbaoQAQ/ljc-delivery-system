package ljc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;

@Data
@TableName("delivery_detail")
public class DeliveryDetail {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String shopId;
    private String skuId;
    private Integer qty;
    private LocalDate date;
    // 新增：批次版本号字段
    private String batchNo;
}