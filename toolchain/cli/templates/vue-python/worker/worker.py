from fengyu_plugin_sdk import Worker


def hello(params, _context):
    return {"message": f"Hello, {params['name']}"}


if __name__ == "__main__":
    Worker().on("hello", hello).run()
