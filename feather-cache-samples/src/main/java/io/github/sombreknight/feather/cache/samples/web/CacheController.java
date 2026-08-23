package io.github.sombreknight.feather.cache.samples.web;

import io.github.sombreknight.feather.cache.samples.domain.Order;
import io.github.sombreknight.feather.cache.samples.service.LockDemoService;
import io.github.sombreknight.feather.cache.samples.service.OrderCacheService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 演示 REST 接口。
 */
@RestController
@RequestMapping("/demo")
public class CacheController {

    @Resource
    private OrderCacheService orderCacheService;

    @Resource
    private LockDemoService lockDemoService;

    /**
     * GET /demo/order/1 —— 多级缓存单查（本地 10s → Redis 5min → DB）
     */
    @GetMapping("/order/{id}")
    public Order getOrder(@PathVariable String id) {
        return orderCacheService.getById(id);
    }

    /**
     * GET /demo/order/null-safe/1 —— Redis 单层 + 空值防穿透
     */
    @GetMapping("/order/null-safe/{id}")
    public Order getOrderNullSafe(@PathVariable String id) {
        return orderCacheService.getByIdNullSafe(id);
    }

    /**
     * GET /demo/orders?ids=1,2,3 —— 批量查询（pipeline + 批量回源）
     */
    @GetMapping("/orders")
    public Map<String, Order> getOrders(@RequestParam("ids") List<String> ids) {
        return orderCacheService.getByIds(ids);
    }

    /**
     * POST /demo/order/1 —— 更新并刷新缓存
     */
    @PostMapping("/order/{id}")
    public Order updateOrder(@PathVariable String id, @RequestBody Order order) {
        order.setId(id);
        orderCacheService.refresh(id, order);
        return order;
    }

    /**
     * DELETE /demo/order/1 —— 删除并清缓存
     */
    @DeleteMapping("/order/{id}")
    public void deleteOrder(@PathVariable String id) {
        orderCacheService.delete(id);
    }

    /**
     * POST /demo/pay/1 —— try-with-resources 分布式锁
     */
    @PostMapping("/pay/{id}")
    public String pay(@PathVariable String id) {
        return lockDemoService.payWithLock(id);
    }

    /**
     * POST /demo/pay-try/1 —— tryLock 非阻塞
     */
    @PostMapping("/pay-try/{id}")
    public String payTry(@PathVariable String id) {
        return lockDemoService.tryPayWithLock(id);
    }

    /**
     * POST /demo/pay-execute/1 —— execute 模板方法
     */
    @PostMapping("/pay-execute/{id}")
    public String payExecute(@PathVariable String id) {
        return lockDemoService.executePay(id);
    }
}
