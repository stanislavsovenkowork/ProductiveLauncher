using Commerce.Notifications;
namespace Commerce.Checkout;
/// <summary>Оформляет и отменяет заказы.</summary>
public class CheckoutService(CartRepository carts, PaymentGateway payments, OrderRepository orders, NotificationService notifications)
{
    /// <summary>Загружает корзину, проводит оплату и отправляет подтверждение.</summary>
    public async Task<Order> PlaceOrder(Guid userId, Guid cartId)
    {
        var cart = await carts.GetById(cartId);
        var total = await CalculateTotal(cart.Id);
        var paymentId = await payments.Charge(userId, total);
        var order = Order.Create(userId, paymentId);
        await orders.Save(order);
        await notifications.SendConfirmation(order);
        return order;
    }
    /// <summary>Считает стоимость всех позиций корзины.</summary>
    public async Task<decimal> CalculateTotal(Guid cartId)
    {
        var cart = await carts.GetById(cartId);
        return cart.Items.Sum(item => item.Price * item.Quantity);
    }
    public async Task CancelOrder(Guid orderId)
    {
        var order = await orders.Find(orderId);
        await payments.Refund(order.PaymentId);
        order.Cancel();
        await orders.Save(order);
    }
}
