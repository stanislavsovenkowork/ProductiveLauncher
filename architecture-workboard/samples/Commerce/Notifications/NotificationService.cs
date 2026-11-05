using Commerce.Checkout;
namespace Commerce.Notifications;
public class NotificationService(EmailSender email)
{
    public Task SendConfirmation(Order order) => email.Send(order.UserId, $"Order {order.Id} confirmed");
}
public class EmailSender
{
    public Task Send(Guid userId, string message) { Console.WriteLine(message); return Task.CompletedTask; }
}
