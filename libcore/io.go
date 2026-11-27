package libcore

import (
	"archive/zip"
	"io"
	"os"
        "path/filepath"

	"github.com/ulikunitz/xz"
	"github.com/sagernet/sing/common"
        E "github.com/sagernet/sing/common/exceptions"
)

func Unxz(archive string, path string) error {
	i, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer i.Close()
	r, err := xz.NewReader(i)
	if err != nil {
		return err
	}
	tmpPath := path + ".tmp"
	_ = os.Remove(tmpPath)
	o, err := os.Create(tmpPath)
	if err != nil {
		return err
	}
	_, err = io.Copy(o, r)
	if syncErr := o.Sync(); err == nil && syncErr != nil {
		err = syncErr
	}
	if closeErr := o.Close(); err == nil && closeErr != nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	if renameErr := os.Rename(tmpPath, path); renameErr != nil {
		_ = os.Remove(tmpPath)
		return renameErr
	}
	return nil
}

func Unzip(archive string, path string) error {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer r.Close()

	err = os.MkdirAll(path, os.ModePerm)
	if err != nil {
		return err
	}

	for _, file := range r.File {
		filePath := filepath.Join(path, file.Name)

		if file.FileInfo().IsDir() {
			err = os.MkdirAll(filePath, os.ModePerm)
			if err != nil {
				return err
			}
			continue
		}

		newFile, err := os.Create(filePath)
		if err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			newFile.Close()
			return err
		}

		var errs error
		_, err = io.Copy(newFile, zipFile)
		errs = E.Errors(errs, err)
		errs = E.Errors(errs, common.Close(zipFile, newFile))
		if errs != nil {
			return errs
		}
	}

	return nil
}
