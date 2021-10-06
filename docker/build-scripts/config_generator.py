# coding=utf-8
import getopt
import logging
import sys
import fnmatch
import os
import time
import shutil
import glob
import requests
import json
import re
from datetime import datetime


# loggers' formatting
_formatter_1 = logging.Formatter('%(asctime)s %(message)s')
_formatter_2 = logging.Formatter('%(name)s - %(levelname)s - %(message)s')


# =====================================

def setup_logger(name, log_file, level=logging.INFO, formatter=_formatter_1):
    handler = logging.FileHandler(log_file, mode='w', encoding='utf-8')
    handler.setFormatter(formatter)

    logger = logging.getLogger(name)
    logger.setLevel(level)
    logger.addHandler(handler)

    return logger


# =====================================

_this_dir = os.path.dirname(os.path.abspath(__file__))
# _logger = setup_logger('primary', '__script.generator.log')
settings = {}

# =====================================

# gen switch
# 0 - general scripts
# 1 - config files
# 2 - docker scripts

settings = {
    "gen_switch": 0,
    "db": "",
    "db_port": 5432,
    "db_user": "postgres",
    "db_pwd": "postgres",
    "db_host": "localhost",
    "config_file_name": "MoquiGenConf.xml",
    "templates_directory": "..\\..\\runtime\\conf\\templates",
    "output_directory": "..\\..\\runtime\\conf\\generated",
    "local_test_dir": "",
    "local_dwh_dir": "",
    "debug": 0,
}


# =====================================

def parse_command_line(env):
    """ Parses the command line arguments. """
    opts = None
    try:
        options = [
            "gen_switch=",
            "db=",
            "db_port=",
            "db_user=",
            "db_pwd=",
            "db_host=",
            "config_file_name=",
            "debug=",
            "erase_first="
        ]
        input_options = sys.argv[1:]
        opts, _ = getopt.getopt(input_options, "", options)
    except getopt.GetoptError as e:
        # _logger.info(u"Invalid arguments [%s]", e)
        sys.exit(1)

    for option, param in opts:
        if option == "--gen_switch":
            env["gen_switch"] = int(param)
        elif option == "--db":
            env["db"] = param
        elif option == "--db_port":
            env["db_port"] = int(param)
        elif option == "--db_user":
            env["db_user"] = param
        elif option == "--db_pwd":
            env["db_pwd"] = param                        
        elif option == "--db_host":
            env["db_host"] = param
        elif option == "--config_file_name":
            env["config_file_name"] = param
        elif option == "--debug":
            env["debug"] = int(param)


# =====================================

def generate_conf_files(config_file_template, config_file_name):
    from string import Template

    #open the `import` templates
    templates_directory = os.path.join(_this_dir, env["templates_directory"])
    output_directory = os.path.join(_this_dir, env["output_directory"])

    # _logger.info("Config file name '%s', config template '%s'" % (env["config_file_name"], config_file_template))

    for filename in os.listdir(templates_directory):
        with open(os.path.join(templates_directory, filename), 'r') as file:
            #quit if not a template file 
            if filename!=config_file_template:
                continue
            
            #read it
            src = Template( file.read() )
        
            #data to use
            d={ 
                'MOQUI_DB': env["db"],
                'MOQUI_DB_PORT': env["db_port"],
                'MOQUI_DB_USER': env["db_user"],
                'MOQUI_DB_PWD': env["db_pwd"],
                'MOQUI_DB_HOST': env["db_host"]
            }
        
            #do the substitution
            result = src.substitute(d)

            # set new file name, if not passed from above
            if config_file_name=='':
                config_file_name = "__" + filename

            with open(os.path.join(output_directory, config_file_name), 'w') as file_to_write:
                file_to_write.write(result)
                file_to_write.close()


# =====================================

def generate_scripts_from_templates(subdir1, subdir2="", filter_reg="*"):
    from string import Template

    # open the `import` templates
    templates_directory = os.path.join(_this_dir, env["templates_directory"], subdir1, subdir2)
    output_directory = os.path.join(_this_dir, env["output_directory"])

    files = glob.glob(templates_directory + filter_reg)

    for filename in files:
        with open(filename, 'r', encoding='utf-8') as file:
            src = Template( file.read() )
            d={
                'db': env['db'],
            }
        
            # do the substitution
            result = src.substitute(d)

            name, ext = os.path.splitext(os.path.basename(filename))
            new_file_name = '{}{}'.format(name, ext)

            # create directory if does not exist
            out_dir_full = os.path.join(output_directory, subdir1)
            if subdir2:
                out_dir_full = os.path.join(output_directory, subdir1, subdir2)

            if not os.path.exists(out_dir_full):
                os.mkdir(out_dir_full)

            with open(os.path.join(out_dir_full, new_file_name), 'w', encoding='utf-8') as file_to_write:
                file_to_write.write(result)
                file_to_write.close()

                
# =====================================

if __name__ == '__main__':
    # _logger.info("Starting config generator")
    
    # load default settings into `env`
    env = settings

    # let the command line params overide defaults
    parse_command_line(env)

    # data to be imported
    if env["gen_switch"] == 1:
        generate_conf_files("DevTestConf.tmpl.xml", env["config_file_name"])
        
        if env["debug"] == 1:
            generate_scripts_from_templates("import", "", "*_DEBUG.*")
        else:
            generate_scripts_from_templates("import", "", "*_NONDEBUG.*")
    elif env["gen_switch"] == 2:
        generate_scripts_from_templates("build_scripts", "docker")
    else:
        generate_scripts_from_templates("build_scripts", "install")
        # generate_scripts_from_templates("build_scripts", "run")
    
    ret=1

    # exit
    sys.exit(ret)