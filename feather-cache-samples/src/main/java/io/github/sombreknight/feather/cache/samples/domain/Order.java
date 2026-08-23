package io.github.sombreknight.feather.cache.samples.domain;

/**
 * 示例领域对象：订单。
 */
public class Order {

    private String id;
    private String status;

    public Order() {
    }

    public Order(String id, String status) {
        this.id = id;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
