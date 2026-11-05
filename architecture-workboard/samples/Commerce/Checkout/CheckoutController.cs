using Commerce.Identity;
namespace Commerce.Checkout;
public class CheckoutController(CheckoutService checkout, IdentityService identity)
{
    public async Task<Order> Submit(Guid cartId)
    {
        var user = await identity.GetCurrentUser();
        return await checkout.PlaceOrder(user.Id, cartId);
    }
    public Task<decimal> Preview(Guid cartId) => checkout.CalculateTotal(cartId);
}
