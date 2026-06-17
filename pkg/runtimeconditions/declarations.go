// Package runtimeconditions provides typed declaration helpers for Runtime
// Conditions Profile generation.
//
// These helpers are intentionally no-op at runtime. The static profiler reads
// calls to this package from Go source and converts them to profile YAML.
package runtimeconditions

// Declaration is the inert value returned by top-level condition declarations.
type Declaration struct{}

// Engine identifies a concrete integration engine within an interface family.
type Engine string

const (
	Postgres  Engine = "postgres"
	MySQL     Engine = "mysql"
	MariaDB   Engine = "mariadb"
	SQLServer Engine = "sqlserver"
	Oracle    Engine = "oracle"
	SQLite    Engine = "sqlite"

	MongoDB   Engine = "mongodb"
	Couchbase Engine = "couchbase"

	Redis     Engine = "redis"
	Memcached Engine = "memcached"

	NATS Engine = "nats"
)

// APIOption configures an API declaration.
type APIOption interface {
	apiOption()
}

// OperationOption configures an API operation declaration.
type OperationOption interface {
	operationOption()
}

type apiOption struct{}

func (apiOption) apiOption() {}

type operationOption struct{}

func (operationOption) operationOption() {}

type schemaOption struct{}

func (schemaOption) apiOption()       {}
func (schemaOption) operationOption() {}

// API declares an external API dependency.
func API(name string, options ...APIOption) Declaration {
	return Declaration{}
}

// Spec declares an external API specification reference.
func Spec(format, uri string, version ...string) APIOption {
	return apiOption{}
}

// GET declares an HTTP GET operation dependency.
func GET(path string, options ...OperationOption) APIOption {
	return apiOption{}
}

// HEAD declares an HTTP HEAD operation dependency.
func HEAD(path string, options ...OperationOption) APIOption {
	return apiOption{}
}

// POST declares an HTTP POST operation dependency.
func POST(path string, options ...OperationOption) APIOption {
	return apiOption{}
}

// PUT declares an HTTP PUT operation dependency.
func PUT(path string, options ...OperationOption) APIOption {
	return apiOption{}
}

// PATCH declares an HTTP PATCH operation dependency.
func PATCH(path string, options ...OperationOption) APIOption {
	return apiOption{}
}

// DELETE declares an HTTP DELETE operation dependency.
func DELETE(path string, options ...OperationOption) APIOption {
	return apiOption{}
}

// OPTIONS declares an HTTP OPTIONS operation dependency.
func OPTIONS(path string, options ...OperationOption) APIOption {
	return apiOption{}
}

// TRACE declares an HTTP TRACE operation dependency.
func TRACE(path string, options ...OperationOption) APIOption {
	return apiOption{}
}

// Request attaches a request body schema based on T.
func Request[T any]() schemaOption {
	return schemaOption{}
}

// Response attaches a response body schema based on T.
func Response[T any]() schemaOption {
	return schemaOption{}
}

// DatastoreOption configures a datastore declaration.
type DatastoreOption interface {
	datastoreOption()
}

type datastoreOption struct{}

func (datastoreOption) datastoreOption() {}

// Datastore declares a persistent datastore dependency.
func Datastore(name string, options ...DatastoreOption) Declaration {
	return Declaration{}
}

// Relational declares a relational datastore interface and engine.
func Relational(engine Engine) DatastoreOption {
	return datastoreOption{}
}

// Document declares a document datastore interface and engine.
func Document(engine Engine) DatastoreOption {
	return datastoreOption{}
}

// CacheOption configures a cache declaration.
type CacheOption interface {
	cacheOption()
}

type cacheOption struct{}

func (cacheOption) cacheOption() {}

// Cache declares a volatile cache dependency.
func Cache(name string, options ...CacheOption) Declaration {
	return Declaration{}
}

// KeyValue declares a key/value cache interface and engine.
func KeyValue(engine Engine) CacheOption {
	return cacheOption{}
}

// MessageBusOption configures a message bus declaration.
type MessageBusOption interface {
	messageBusOption()
}

// SubjectOption configures a message bus subject declaration.
type SubjectOption interface {
	subjectOption()
}

type messageBusOption struct{}

func (messageBusOption) messageBusOption() {}

type subjectOption struct{}

func (subjectOption) subjectOption() {}

// MessageBus declares an extension-defined message bus dependency.
func MessageBus(name string, options ...MessageBusOption) Declaration {
	return Declaration{}
}

// PubSub declares a publish/subscribe message bus interface and engine.
func PubSub(engine Engine) MessageBusOption {
	return messageBusOption{}
}

// Publishes declares a subject the workload publishes to.
func Publishes(subject string, options ...SubjectOption) MessageBusOption {
	return messageBusOption{}
}

// Subscribes declares a subject the workload subscribes to.
func Subscribes(subject string, options ...SubjectOption) MessageBusOption {
	return messageBusOption{}
}

// Payload attaches a message payload schema based on T.
func Payload[T any]() SubjectOption {
	return subjectOption{}
}
