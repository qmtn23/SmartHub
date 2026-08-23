package com.hmdp.dto.tool;

import lombok.Data;

@Data
public class ShopToolDTO {
    private Long shopId;
    private String name;
    private Long typeId;
    private String address;
    private String area;
    private Long avgPrice;
    private Double score;
    private Integer sold;
    private Integer comments;
    private String openHours;
}
