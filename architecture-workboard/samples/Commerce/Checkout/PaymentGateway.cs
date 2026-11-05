namespace Commerce.Checkout;
public interface IPaymentClient
{
    Task<string> CreatePayment(Guid userId, decimal amount);
    Task RefundPayment(string id);
}
public class PaymentGateway(IPaymentClient client)
{
    /// <summary>Оплата через интерфейс провайдера; реализация выбирается при запуске.</summary>
    public Task<string> Charge(Guid userId, decimal amount) => client.CreatePayment(userId, amount);
    public Task Refund(string paymentId) => client.RefundPayment(paymentId);
}
