namespace Commerce.Identity;
public record User(Guid Id, string Name);
public class IdentityService(UserRepository users)
{
    public Task<User> GetCurrentUser() => users.FindUser(Guid.Empty);
}
public class UserRepository
{
    public Task<User> FindUser(Guid id) => Task.FromResult(new User(id, "Sample user"));
}
