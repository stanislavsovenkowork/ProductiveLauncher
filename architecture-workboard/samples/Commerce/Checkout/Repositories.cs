namespace Commerce.Checkout;
public record CartItem(decimal Price, int Quantity);
public record Cart(Guid Id, List<CartItem> Items);
public class CartRepository
{
    public Task<Cart> GetById(Guid id) => Task.FromResult(new Cart(id, [new CartItem(25, 2)]));
    public async Task Clear(Guid id) { var cart = await GetById(id); cart.Items.Clear(); }
}
public class OrderRepository
{
    private readonly Dictionary<Guid, Order> storage = new();
    public Task Save(Order order) { storage[order.Id] = order; return Task.CompletedTask; }
    public Task<Order> Find(Guid id) => Task.FromResult(storage[id]);
}
public class Order
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public Guid UserId { get; init; }
    public string PaymentId { get; init; } = "";
    public bool IsCancelled { get; private set; }
    public static Order Create(Guid userId, string paymentId) => new() { UserId = userId, PaymentId = paymentId };
    public void Cancel() { IsCancelled = true; }
}
