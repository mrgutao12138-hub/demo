package com.example.decision;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 当前登录用户可访问的仓库白名单。
 * 真实项目从 SSO / 权限中心加载；演示用内存映射。
 */
@Service
public class WarehouseAccessService {

    private static final String DEMO_TENANT = "TENANT-DEMO";

    /** userId -> 可访问 warehouseId 列表 */
    private static final java.util.Map<String, List<String>> USER_WAREHOUSES = java.util.Map.of(
            "user-east",  List.of("WH-EAST"),
            "user-south", List.of("WH-SOUTH"),
            "user-multi", List.of("WH-EAST", "WH-SOUTH", "WH-NORTH"),
            "user-demo",  List.of("WH-EAST", "WH-SOUTH", "WH-NORTH")
    );

    public String currentTenantId() {
        // 演示固定租户；生产从 SecurityContext / 租户拦截器读取
        return DEMO_TENANT;
    }

    public String currentUserId() {
        // 演示默认 user-demo；生产从 JWT / Session 读取
        return "user-demo";
    }

    public Set<String> accessibleWarehouseIds() {
        return Set.copyOf(USER_WAREHOUSES.getOrDefault(currentUserId(), List.of()));
    }

    public void assertWarehouseAccessible(String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            throw new IllegalArgumentException("warehouseId 不能为空");
        }
        if (!accessibleWarehouseIds().contains(warehouseId)) {
            throw new SecurityException("无权访问仓库: " + warehouseId);
        }
    }

    public void assertWarehousesAccessible(List<String> warehouseIds) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            throw new IllegalArgumentException("warehouseIds 不能为空");
        }
        for (String id : warehouseIds) {
            assertWarehouseAccessible(id);
        }
    }
}
