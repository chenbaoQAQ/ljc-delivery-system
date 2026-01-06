package ljc.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("delivery_template")
public class DeliveryTemplate {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String templateName;
    private Integer dayOfMonth;
    private Integer isDeliver;
}