const questionnaire = document.querySelector('.questionnaire');

if (questionnaire) {
    const fields = [...questionnaire.querySelectorAll('.question-field')];
    const progress = document.querySelector('#question-progress');
    const progressText = progress.querySelector('p');
    const meter = progress.querySelector('progress');

    const updateProgress = () => {
        const answered = fields.filter(field => {
            const input = field.querySelector('input[type="text"], input[type="radio"]:checked');
            return input && input.value.trim() !== '';
        }).length;
        progressText.textContent = `${answered} of ${fields.length} answered`;
        meter.value = answered;
    };

    progress.hidden = false;
    questionnaire.addEventListener('input', updateProgress);
    updateProgress();

    const picker = document.querySelector('#language-picker');
    const language = picker.querySelector('select');
    const strings = [...questionnaire.querySelectorAll('[data-hi], [data-pa], [data-ta]')]
        .map(node => ({node, english: node.textContent}));

    const translate = () => {
        const locale = language.value;
        strings.forEach(({node, english}) => {
            const translation = node.dataset[locale];
            node.textContent = translation ?? english;
            node.lang = translation === undefined ? 'en' : locale;
        });
    };

    picker.hidden = false;
    language.addEventListener('change', translate);
}

const back = document.querySelector('[data-back]');
if (back && document.referrer && new URL(document.referrer).origin === location.origin
    && new URL(document.referrer).pathname.startsWith('/check/')) {
    back.hidden = false;
    back.addEventListener('click', () => history.back());
}
