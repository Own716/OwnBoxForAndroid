package libcore

// StringIterator / NetworkInterfaceIterator 是 gomobile 桥接类型（Kotlin 侧实现）。
// 参考 husi libcore/iterator.go。

type StringIterator interface {
	Next() string
	HasNext() bool
	Length() int32
}

type abstractIterator[T any] interface {
	Next() T
	HasNext() bool
	Length() int32
}

func iteratorToArray[T any](iterator abstractIterator[T]) []T {
	if iterator == nil {
		return nil
	}
	values := make([]T, 0, iterator.Length())
	for iterator.HasNext() {
		values = append(values, iterator.Next())
	}
	return values
}
