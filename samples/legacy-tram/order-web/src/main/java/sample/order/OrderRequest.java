package sample.order;

public class OrderRequest {
    private String userId;
    private int amount;

    public OrderRequest() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
}
