package ljc.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("delivery_template")
public class DeliveryTemplate {
    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("`template_name`") // 强制添加反引号
    private String templateName;

    @TableField("`year_month`")    // 重点：强制添加反引号解决解析歧义
    private String yearMonth;

    @TableField("`config`")        // 建议 config 也加上
    private String config;
}