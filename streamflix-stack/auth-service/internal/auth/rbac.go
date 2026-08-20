package auth

// Permission is a single, granular thing a role may be allowed to do.
// Keeping permissions granular (rather than just checking role name
// directly in handlers) is what lets access rules change later without
// touching every handler that checks them.
type Permission string

const (
	PermUsersRead    Permission = "users:read"
	PermUsersWrite   Permission = "users:write"
	PermDevicesRead  Permission = "devices:read"
	PermDevicesWrite Permission = "devices:write"
	PermDevicesAdmin Permission = "devices:admin"
)

// rolePermissions is the RBAC table itself: which roles carry which
// permissions. In a real system this table (or its equivalent) is usually
// the single most product-sensitive piece of the whole identity service --
// "who can do what" is a product decision as much as an engineering one,
// which is exactly why Role B's posting calls out working with product
// leadership on this kind of system.
var rolePermissions = map[string][]Permission{
	"admin":    {PermUsersRead, PermUsersWrite, PermDevicesRead, PermDevicesWrite, PermDevicesAdmin},
	"operator": {PermDevicesRead, PermDevicesWrite},
	"viewer":   {PermDevicesRead},
}

// HasPermission is deliberately a pure function: no HTTP, no I/O, just a
// role name and a permission in, a bool out. That's what makes it trivial
// to unit test exhaustively (see rbac_test.go) without spinning up a server.
func HasPermission(role string, perm Permission) bool {
	perms, ok := rolePermissions[role]
	if !ok {
		return false
	}
	for _, p := range perms {
		if p == perm {
			return true
		}
	}
	return false
}
